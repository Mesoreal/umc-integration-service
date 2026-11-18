package ru.provless.integration.review;

import org.junit.jupiter.api.Test;
import ru.provless.integration.umc.entity.DocumentReview;
import ru.provless.integration.umc.repository.DocumentReviewRepository;
import ru.provless.integration.umc.service.DocumentPassedPrecheckEvent;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Who checks a document (front requests S5 §10): a person's — amoCRM, not Chatwoot; an organization's — Chatwoot. */
class ReviewRoutingTest {

    private final DocumentReviewRepository documentReviews = mock(DocumentReviewRepository.class);
    private final ChatwootReviews reviews = mock(ChatwootReviews.class);
    private final ReviewRouting routing = new ReviewRouting(documentReviews, reviews);

    @Test
    void organizationsDocument_toChatwoot() {
        DocumentReview review = review("LEGAL");
        routing.onDocumentPassedPrecheck(new DocumentPassedPrecheckEvent(review.getId()));
        verify(reviews).open(RejectionReasons.Kind.DOCUMENT, review.getDocumentId(), review.getUserId(), review.getId());
    }

    @Test
    void personsDocument_andOneFromBefore10_notToChatwoot() {
        for (String type : new String[]{"PHYSICAL", null}) {
            DocumentReview review = review(type);
            routing.onDocumentPassedPrecheck(new DocumentPassedPrecheckEvent(review.getId()));
        }
        verify(reviews, never()).open(any(), any(), any(), any());
    }

    private DocumentReview review(String profileType) {
        DocumentReview review = new DocumentReview();
        review.setId(UUID.randomUUID());
        review.setDocumentId(UUID.randomUUID());
        review.setUserId(UUID.randomUUID());
        review.setProfileType(profileType);
        when(documentReviews.findById(review.getId())).thenReturn(Optional.of(review));
        return review;
    }
}
