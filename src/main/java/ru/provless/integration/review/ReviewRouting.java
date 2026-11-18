package ru.provless.integration.review;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.provless.integration.common.config.RabbitConfig;
import ru.provless.integration.umc.entity.DocumentReview;
import ru.provless.integration.umc.repository.DocumentReviewRepository;
import ru.provless.integration.umc.service.DocumentPassedPrecheckEvent;

import java.util.UUID;

/**
 * Who checks what (front requests S5 §10, decided 18.11.2026): documents of a person — the training centre in amoCRM
 * ({@code AmoCrmLeadService}); documents of an organization's profile and LEGAL / IE payers — sales in the Chatwoot inbox
 * «Проверка документов». A self-employed payer follows the diploma and needs no request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewRouting {

    private final DocumentReviewRepository documentReviews;
    private final ChatwootReviews reviews;

    /** profile-service, a new LEGAL / IE payer. */
    public record PayerAddedEvent(UUID userId, UUID organizationId) {
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentPassedPrecheck(DocumentPassedPrecheckEvent event) {
        DocumentReview review = documentReviews.findById(event.reviewId()).orElse(null);
        if (review == null || review.physical()) {
            return;
        }
        reviews.open(RejectionReasons.Kind.DOCUMENT, review.getDocumentId(), review.getUserId(), review.getId());
    }

    @RabbitListener(queues = RabbitConfig.PROFILE_PAYER_ADDED_QUEUE)
    public void onPayerAdded(PayerAddedEvent event) {
        log.info("profile.payer-added: userId={} organizationId={}", event.userId(), event.organizationId());
        reviews.open(RejectionReasons.Kind.PAYER, event.organizationId(), event.userId(), null);
    }
}
