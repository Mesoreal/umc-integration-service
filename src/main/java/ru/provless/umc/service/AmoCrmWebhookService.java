package ru.provless.umc.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import ru.provless.umc.dto.AmoCrmLeadStatusChange;
import ru.provless.umc.entity.DocumentReview;
import ru.provless.umc.entity.DocumentReviewAuditEventType;
import ru.provless.umc.entity.DocumentReviewStatus;
import ru.provless.umc.repository.DocumentReviewRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 8 of the plan: amoCRM's webhook when a deal's stage changes in the "Проверка
 * документов" pipeline. Only updates umc-integration-service's own {@code document_review}
 * status — publishing {@code document.approved}/{@code document.rejected} to profile-service
 * is Phase 4, not this.
 *
 * amoCRM's classic (Settings → Webhooks) callback has no built-in payload signature, so
 * authenticity here relies on a secret embedded in the callback URL path itself (see
 * AmoCrmWebhookController) plus an optional IP allowlist — not the "secret header" the plan
 * originally sketched, since nothing guarantees amoCRM actually sends one for this webhook
 * type. Idempotency is effect-based (skip if the review is already in the target status)
 * rather than by a canonical event id, because amoCRM's classic webhook payload doesn't
 * carry one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AmoCrmWebhookService {

    private static final Pattern LEAD_STATUS_KEY = Pattern.compile("^leads\\[status]\\[(\\d+)]\\[(\\w+)]$");

    @Value("${amocrm.stage-approved-id}")
    private String stageApprovedIdRaw;

    @Value("${amocrm.stage-rejected-id}")
    private String stageRejectedIdRaw;

    private final DocumentReviewRepository documentReviewRepository;
    private final DocumentReviewAuditWriter auditWriter;

    @Transactional
    public void handle(MultiValueMap<String, String> form) {
        List<AmoCrmLeadStatusChange> changes = parseLeadStatusChanges(form);
        if (changes.isEmpty()) {
            log.debug("amoCRM webhook carried no leads[status][...] entries — ignoring (probably a different event type)");
        }
        for (AmoCrmLeadStatusChange change : changes) {
            processOne(change);
        }
    }

    private void processOne(AmoCrmLeadStatusChange change) {
        DocumentReview review = documentReviewRepository.findByAmocrmLeadId(change.leadId()).orElse(null);
        if (review == null) {
            log.debug("amoCRM webhook for leadId={} — not one of ours, ignoring", change.leadId());
            return;
        }

        long approvedId = parseOrMinusOne(stageApprovedIdRaw);
        long rejectedId = parseOrMinusOne(stageRejectedIdRaw);
        DocumentReviewStatus newStatus;
        if (change.statusId() == approvedId) {
            newStatus = DocumentReviewStatus.APPROVED;
        } else if (change.statusId() == rejectedId) {
            newStatus = DocumentReviewStatus.REJECTED;
        } else {
            auditWriter.write(review, DocumentReviewAuditEventType.CRM_STATUS_CHANGED,
                    Map.of("statusId", change.statusId(), "ignored", true));
            return;
        }

        if (review.getStatus() == newStatus) {
            log.debug("DocumentReview {} already {} — duplicate webhook delivery, skipping", review.getId(), newStatus);
            return;
        }
        if (review.getStatus() == DocumentReviewStatus.APPROVED || review.getStatus() == DocumentReviewStatus.REJECTED) {
            // Reverting a final decision isn't handled yet — see the plan's open question
            // ("Ревёрт решения менеджером"). Record it rather than silently flipping the status.
            log.warn("DocumentReview {} is already in terminal status {} — refusing to move to {} without a revert flow",
                    review.getId(), review.getStatus(), newStatus);
            auditWriter.write(review, DocumentReviewAuditEventType.CRM_STATUS_CHANGED,
                    Map.of("statusId", change.statusId(), "attemptedStatus", newStatus.name(),
                            "refused", true, "reason", "already_terminal:" + review.getStatus()));
            return;
        }

        review.setStatus(newStatus);
        documentReviewRepository.save(review);

        String actor = change.modifiedUserId() != null ? "amocrm_user:" + change.modifiedUserId() : "system";
        auditWriter.write(review, DocumentReviewAuditEventType.CRM_STATUS_CHANGED,
                Map.of("statusId", change.statusId(), "newStatus", newStatus.name()), actor);
        log.info("DocumentReview {} moved to {} via amoCRM webhook (lead {})", review.getId(), newStatus, change.leadId());
    }

    /**
     * amoCRM posts {@code application/x-www-form-urlencoded} with bracketed array keys —
     * e.g. {@code leads[status][0][id]=123&leads[status][0][status_id]=456} — not JSON, and
     * a single callback can carry several leads at once.
     */
    private List<AmoCrmLeadStatusChange> parseLeadStatusChanges(MultiValueMap<String, String> form) {
        Map<Integer, Map<String, String>> byIndex = new TreeMap<>();
        for (String key : form.keySet()) {
            Matcher matcher = LEAD_STATUS_KEY.matcher(key);
            if (!matcher.matches()) {
                continue;
            }
            int index = Integer.parseInt(matcher.group(1));
            String field = matcher.group(2);
            byIndex.computeIfAbsent(index, i -> new java.util.HashMap<>()).put(field, form.getFirst(key));
        }

        List<AmoCrmLeadStatusChange> changes = new ArrayList<>();
        for (Map<String, String> fields : byIndex.values()) {
            try {
                changes.add(new AmoCrmLeadStatusChange(
                        Long.parseLong(fields.get("id")),
                        Long.parseLong(fields.get("status_id")),
                        Long.parseLong(fields.getOrDefault("pipeline_id", "0")),
                        fields.get("modified_user_id") != null ? Long.parseLong(fields.get("modified_user_id")) : null
                ));
            } catch (NumberFormatException | NullPointerException e) {
                log.warn("Malformed leads[status] entry in amoCRM webhook: {}", fields, e);
            }
        }
        return changes;
    }

    private long parseOrMinusOne(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
