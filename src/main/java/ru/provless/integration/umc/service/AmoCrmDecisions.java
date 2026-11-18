package ru.provless.integration.umc.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.provless.integration.amocrm.AmoCrmClient;
import ru.provless.integration.profile.ProfileServiceClient;
import ru.provless.integration.review.RejectionReasons;
import ru.provless.integration.umc.entity.DocumentReview;
import ru.provless.integration.umc.entity.DocumentReviewAuditEventType;
import ru.provless.integration.umc.entity.DocumentReviewStatus;
import ru.provless.integration.umc.repository.DocumentReviewRepository;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 4 (front requests S5 §10): a deal moved to «Одобрено» / «Отклонено» → the decision goes to profile-service. The reason,
 * the comment and the diploma's summary are read from the deal when sending (the manager may fill them right after moving it).
 * The reason is the text of the list field; it is matched to Directus «Причины отклонения» — unknown text (removed from Directus)
 * goes as «Другая причина» with that text as the comment. A rejection without a reason and comment still goes, asking the client to
 * write to the manager (make the field required for the stage in amoCRM, see the k8s readme).
 *
 * <p>Sent right after the webhook and retried by a pass every few minutes until profile-service has it; the document deleted by the
 * client — nothing to send.
 */
@Slf4j
@Service
public class AmoCrmDecisions {

    static final String NO_REASON = "Причина не указана — напишите менеджеру в чат";

    public record Decided(UUID reviewId) {
    }

    private final DocumentReviewRepository reviews;
    private final DocumentReviewAuditWriter audit;
    private final AmoCrmClient amo;
    private final RejectionReasons reasons;
    private final ProfileServiceClient profiles;

    public AmoCrmDecisions(DocumentReviewRepository reviews, DocumentReviewAuditWriter audit, AmoCrmClient amo,
                           RejectionReasons reasons, ProfileServiceClient profiles) {
        this.reviews = reviews;
        this.audit = audit;
        this.amo = amo;
        this.reasons = reasons;
        this.profiles = profiles;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDecided(Decided event) {
        reviews.findById(event.reviewId()).ifPresent(this::send);
    }

    @Scheduled(initialDelayString = "${review.retry-initial-delay:PT1M}", fixedDelayString = "${review.retry-interval:PT5M}")
    public void resendPending() {
        if (!amo.configured()) {
            return;
        }
        reviews.findTop50ByStatusInAndDecisionSentAtIsNullAndAmocrmLeadIdIsNotNull(
                EnumSet.of(DocumentReviewStatus.APPROVED, DocumentReviewStatus.REJECTED)).forEach(this::send);
    }

    void send(DocumentReview review) {
        if (review.getDecisionSentAt() != null || review.getAmocrmLeadId() == null) {
            return;
        }
        boolean approved = review.getStatus() == DocumentReviewStatus.APPROVED;
        try {
            AmoCrmClient.LeadDecision lead = amo.leadDecision(review.getAmocrmLeadId());
            ProfileServiceClient.Decision decision = approved ? ProfileServiceClient.Decision.approve(lead.summary()) : rejection(lead);
            review.setDecisionReasonCode(decision.reason());
            review.setDecisionReasonText(decision.reasonText());
            review.setDecisionComment(decision.comment());
            review.setDecisionSummary(decision.summary());

            String outcome;
            try {
                profiles.decideDocument(review.getUserId(), review.getDocumentId(), decision);
                outcome = "sent";
            } catch (ProfileServiceClient.DecisionRefused e) {
                // the document is gone (404) or the decision itself is wrong (400): resending would not help
                outcome = e.gone() ? "document_gone" : "refused: " + e.getMessage();
                log.warn("profile-service did not take the decision on documentReview {} ({}): {}", review.getId(), e.status(), e.getMessage());
            }
            review.setDecisionSentAt(OffsetDateTime.now());
            reviews.save(review);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", review.getStatus().name());
            payload.put("reason", decision.reason());
            payload.put("outcome", outcome);
            audit.write(review, DocumentReviewAuditEventType.STATUS_PUBLISHED, payload);
            log.info("Decision on documentReview {} (lead {}): {} — {}", review.getId(), review.getAmocrmLeadId(), review.getStatus(), outcome);
        } catch (RuntimeException e) {
            log.warn("Decision on documentReview {} not sent yet, the next pass retries: {}", review.getId(), e.getMessage());
        }
    }

    ProfileServiceClient.Decision rejection(AmoCrmClient.LeadDecision lead) {
        String comment = lead.comment();
        if (lead.reasonText() == null) {
            return ProfileServiceClient.Decision.reject(RejectionReasons.OTHER, RejectionReasons.OTHER_TEXT, comment != null ? comment : NO_REASON);
        }
        return reasons.byText(lead.reasonText())
                .map(r -> RejectionReasons.OTHER.equals(r.code())
                        ? ProfileServiceClient.Decision.reject(r.code(), r.text(), comment != null ? comment : NO_REASON)
                        : ProfileServiceClient.Decision.reject(r.code(), r.text(), comment))
                .orElseGet(() -> ProfileServiceClient.Decision.reject(RejectionReasons.OTHER, RejectionReasons.OTHER_TEXT,
                        comment == null ? lead.reasonText() : lead.reasonText() + ". " + comment));
    }
}
