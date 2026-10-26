package ru.provless.integration.umc.service;

import ru.provless.integration.umc.entity.DocumentReview;
import ru.provless.integration.umc.entity.DocumentReviewAudit;
import ru.provless.integration.umc.entity.DocumentReviewAuditEventType;
import ru.provless.integration.umc.repository.DocumentReviewAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class DocumentReviewAuditWriter {

    private final DocumentReviewAuditRepository auditRepository;

    public void write(DocumentReview review, DocumentReviewAuditEventType eventType, Map<String, Object> payload) {
        write(review, eventType, payload, "system");
    }

    /** @param actor {@code system}, or {@code amocrm_user:<id>} when a manager's action in amoCRM drove this event. */
    public void write(DocumentReview review, DocumentReviewAuditEventType eventType, Map<String, Object> payload, String actor) {
        DocumentReviewAudit audit = new DocumentReviewAudit();
        audit.setDocumentReviewId(review.getId());
        audit.setEventType(eventType);
        audit.setPayload(payload);
        audit.setActor(actor);
        auditRepository.save(audit);
    }
}
