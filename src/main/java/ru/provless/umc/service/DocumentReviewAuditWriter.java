package ru.provless.umc.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.provless.umc.entity.DocumentReview;
import ru.provless.umc.entity.DocumentReviewAudit;
import ru.provless.umc.entity.DocumentReviewAuditEventType;
import ru.provless.umc.repository.DocumentReviewAuditRepository;

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
