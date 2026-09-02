package ru.provless.umc.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.provless.umc.client.ContactInfo;
import ru.provless.umc.client.ProfileServiceClient;
import ru.provless.umc.client.amocrm.AmoCrmClient;
import ru.provless.umc.entity.DocumentReview;
import ru.provless.umc.entity.DocumentReviewAudit;
import ru.provless.umc.entity.DocumentReviewAuditEventType;
import ru.provless.umc.repository.DocumentReviewAuditRepository;
import ru.provless.umc.repository.DocumentReviewRepository;

import java.util.Map;

/**
 * Stage 6 of the plan: once precheck passes, create a deal in amoCRM's "Проверка
 * документов" pipeline (background task — see {@link DocumentPassedPrecheckEvent}).
 * Phase 2 scope: one-way sync only, no webhook yet — a manager reads the pipeline
 * manually. Failures here are logged and audited but not retried automatically; there is
 * no recovery path until Phase 3's webhook lands, so ops needs to notice a run of
 * CRM_LEAD_CREATE_FAILED audit rows for now.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AmoCrmLeadService {

    @Value("${umc.public-base-url}")
    private String publicBaseUrl;

    private final DocumentReviewRepository documentReviewRepository;
    private final DocumentReviewAuditRepository auditRepository;
    private final ProfileServiceClient profileServiceClient;
    private final AmoCrmClient amoCrmClient;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentPassedPrecheck(DocumentPassedPrecheckEvent event) {
        DocumentReview review = documentReviewRepository.findById(event.reviewId()).orElse(null);
        if (review == null) {
            log.warn("DocumentReview {} disappeared before amoCRM lead creation", event.reviewId());
            return;
        }
        if (review.getAmocrmLeadId() != null) {
            // precheck() re-running for a document that already has a lead (e.g. profile-service
            // retrying after a fail-open) must not create a second deal for it.
            log.debug("DocumentReview {} already has amoCRM lead {} — skipping", review.getId(), review.getAmocrmLeadId());
            return;
        }

        try {
            ContactInfo contact = profileServiceClient.fetchContactInfo(review.getUserId());
            long contactId = amoCrmClient.findOrCreateContact(contact.phone(), contact.fullName());

            String fileLink = publicBaseUrl + "/files/document-review/" + review.getId() + "/" + review.getFileAccessToken();
            long leadId = amoCrmClient.createLead(new AmoCrmClient.CreateLeadRequest(
                    contactId, review.getDocumentId(), review.getDocumentType().name(), fileLink, review.getOcrConfidence()));

            review.setAmocrmContactId(contactId);
            review.setAmocrmLeadId(leadId);
            documentReviewRepository.save(review);

            writeAudit(review, DocumentReviewAuditEventType.CRM_LEAD_CREATED, Map.of("leadId", leadId, "contactId", contactId));
            log.info("Created amoCRM lead {} (contact {}) for documentReview {}", leadId, contactId, review.getId());
        } catch (Exception e) {
            log.error("Failed to create amoCRM lead for documentReview {} — document stays PENDING_MANAGER with no CRM card; " +
                    "needs manual follow-up until Phase 3's webhook/retry exists", review.getId(), e);
            writeAudit(review, DocumentReviewAuditEventType.CRM_LEAD_CREATED, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void writeAudit(DocumentReview review, DocumentReviewAuditEventType eventType, Map<String, Object> payload) {
        DocumentReviewAudit audit = new DocumentReviewAudit();
        audit.setDocumentReviewId(review.getId());
        audit.setEventType(eventType);
        audit.setPayload(payload);
        audit.setActor("system");
        auditRepository.save(audit);
    }
}
