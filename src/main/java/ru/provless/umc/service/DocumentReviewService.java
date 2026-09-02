package ru.provless.umc.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.provless.umc.client.FileContent;
import ru.provless.umc.client.ProfileServiceClient;
import ru.provless.umc.client.ocr.OcrClient;
import ru.provless.umc.client.ocr.OcrResult;
import ru.provless.umc.dto.PrecheckRequest;
import ru.provless.umc.dto.PrecheckResponse;
import ru.provless.umc.dto.PrecheckVerdict;
import ru.provless.umc.entity.DocumentReview;
import ru.provless.umc.entity.DocumentReviewAudit;
import ru.provless.umc.entity.DocumentReviewAuditEventType;
import ru.provless.umc.entity.DocumentReviewStatus;
import ru.provless.umc.repository.DocumentReviewAuditRepository;
import ru.provless.umc.repository.DocumentReviewRepository;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentReviewService {

    private final ProfileServiceClient profileServiceClient;
    private final OcrClient ocrClient;
    private final DocumentKeywordFilter keywordFilter;
    private final DocumentReviewRepository documentReviewRepository;
    private final DocumentReviewAuditRepository auditRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public PrecheckResponse precheck(PrecheckRequest request) {
        DocumentReview review = documentReviewRepository.findByDocumentId(request.getDocumentId())
                .orElseGet(() -> newReview(request));

        FileContent file = profileServiceClient.fetchFile(request.getUserId(), request.getFileId());

        OcrResult ocrResult = ocrClient.recognize(file.data(), file.contentType());
        DocumentKeywordFilter.Result filterResult = keywordFilter.filter(request.getDocumentType(), ocrResult.text());

        review.setOcrRawText(ocrResult.text());
        review.setOcrConfidence(resolveConfidence(ocrResult, filterResult));
        review.setOcrMatchedKeywords(filterResult.matchedKeywords());
        review.setStatus(filterResult.passed() ? DocumentReviewStatus.PENDING_MANAGER : DocumentReviewStatus.AUTO_REJECTED);
        documentReviewRepository.save(review);

        writeAudit(review, DocumentReviewAuditEventType.OCR_COMPLETED, Map.of(
                "confidence", String.valueOf(review.getOcrConfidence()),
                "matchedKeywords", filterResult.matchedKeywords()
        ));

        if (!filterResult.passed()) {
            writeAudit(review, DocumentReviewAuditEventType.AUTO_REJECTED, Map.of("reason", "no_expected_keywords_found"));
            log.info("Precheck REJECT: documentId={} documentType={}", request.getDocumentId(), request.getDocumentType());
            return PrecheckResponse.builder()
                    .verdict(PrecheckVerdict.REJECT)
                    .reviewId(review.getId())
                    .reason("no_expected_keywords_found")
                    .build();
        }

        log.info("Precheck PASS: documentId={} documentType={} matchedKeywords={}",
                request.getDocumentId(), request.getDocumentType(), filterResult.matchedKeywords());
        // Deferred to AFTER_COMMIT (see AmoCrmLeadListener) — the deal must not be created
        // in amoCRM before this review row is durably saved, and amoCRM's latency must not
        // block the HTTP response back to profile-service (stage 6 of the plan).
        eventPublisher.publishEvent(new DocumentPassedPrecheckEvent(review.getId()));
        return PrecheckResponse.builder()
                .verdict(PrecheckVerdict.PASS)
                .reviewId(review.getId())
                .confidence(review.getOcrConfidence())
                .matchedKeywords(filterResult.matchedKeywords())
                .build();
    }

    private DocumentReview newReview(PrecheckRequest request) {
        DocumentReview review = new DocumentReview();
        review.setDocumentId(request.getDocumentId());
        review.setFileId(request.getFileId());
        review.setProfileId(request.getProfileId());
        review.setUserId(request.getUserId());
        review.setDocumentType(request.getDocumentType());
        review.setStatus(DocumentReviewStatus.OCR_PROCESSING);
        return documentReviewRepository.save(review);
    }

    /**
     * Prefers Yandex's own per-word confidence; when OCR found no words to average
     * (e.g. blank/garbage image) but the keyword filter still passed via the MRZ
     * fallback, 1.00 stands in until real OCR responses are available to calibrate this.
     */
    private BigDecimal resolveConfidence(OcrResult ocrResult, DocumentKeywordFilter.Result filterResult) {
        if (ocrResult.confidence() != null) {
            return ocrResult.confidence();
        }
        return filterResult.passed() ? BigDecimal.ONE : BigDecimal.ZERO;
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
