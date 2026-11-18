package ru.provless.integration.umc.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.provless.integration.amocrm.AmoCrmClient;
import ru.provless.integration.profile.ProfileServiceClient;
import ru.provless.integration.review.RejectionReasons;
import ru.provless.integration.umc.entity.DocumentReview;
import ru.provless.integration.umc.entity.DocumentReviewStatus;
import ru.provless.integration.umc.repository.DocumentReviewRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 4 (front requests S5 §10): the decision of an amoCRM deal → profile-service. */
class AmoCrmDecisionsTest {

    private final DocumentReviewRepository reviews = mock(DocumentReviewRepository.class);
    private final AmoCrmClient amo = mock(AmoCrmClient.class);
    private final RejectionReasons reasons = mock(RejectionReasons.class);
    private final ProfileServiceClient profiles = mock(ProfileServiceClient.class);
    private final AmoCrmDecisions decisions = new AmoCrmDecisions(reviews, mock(DocumentReviewAuditWriter.class), amo, reasons, profiles);

    private DocumentReview review;

    @BeforeEach
    void setUp() {
        review = new DocumentReview();
        review.setId(UUID.randomUUID());
        review.setDocumentId(UUID.randomUUID());
        review.setUserId(UUID.randomUUID());
        review.setAmocrmLeadId(555L);
        when(reviews.findById(review.getId())).thenReturn(Optional.of(review));
        when(reasons.byText("Фото размыто")).thenReturn(Optional.of(new RejectionReasons.Reason("UNREADABLE", "Фото размыто")));
        when(reasons.byText("Удалённая причина")).thenReturn(Optional.empty());
    }

    @Test
    void approved_withTheSummary() {
        review.setStatus(DocumentReviewStatus.APPROVED);
        when(amo.leadDecision(555L)).thenReturn(new AmoCrmClient.LeadDecision(null, null, "Иванова Анна · косметология"));
        decisions.onDecided(new AmoCrmDecisions.Decided(review.getId()));
        verify(profiles).decideDocument(review.getUserId(), review.getDocumentId(),
                ProfileServiceClient.Decision.approve("Иванова Анна · косметология"));
        assertThat(review.getDecisionSentAt()).isNotNull();
    }

    @Test
    void rejected_reasonMatchedToDirectus_unknownOrMissingOne_asOther() {
        review.setStatus(DocumentReviewStatus.REJECTED);
        when(amo.leadDecision(555L)).thenReturn(new AmoCrmClient.LeadDecision("Фото размыто", "Не видна серия", null));
        decisions.send(review);
        verify(profiles).decideDocument(review.getUserId(), review.getDocumentId(),
                ProfileServiceClient.Decision.reject("UNREADABLE", "Фото размыто", "Не видна серия"));

        assertThat(decisions.rejection(new AmoCrmClient.LeadDecision("Удалённая причина", null, null)))
                .isEqualTo(ProfileServiceClient.Decision.reject(RejectionReasons.OTHER, RejectionReasons.OTHER_TEXT, "Удалённая причина"));
        assertThat(decisions.rejection(new AmoCrmClient.LeadDecision(null, null, null)))
                .isEqualTo(ProfileServiceClient.Decision.reject(RejectionReasons.OTHER, RejectionReasons.OTHER_TEXT, AmoCrmDecisions.NO_REASON));
    }

    @Test
    void amoNotAnswering_notMarkedSent_documentGone_markedSent() {
        review.setStatus(DocumentReviewStatus.APPROVED);
        when(amo.leadDecision(555L)).thenThrow(new RuntimeException("amo down"));
        decisions.send(review);
        assertThat(review.getDecisionSentAt()).isNull();
        verify(profiles, never()).decideDocument(any(), any(), any());

        org.mockito.Mockito.doReturn(new AmoCrmClient.LeadDecision(null, null, null)).when(amo).leadDecision(555L);
        doThrow(new ProfileServiceClient.DecisionRefused(404, "")).when(profiles).decideDocument(any(), any(), any());
        decisions.send(review);
        assertThat(review.getDecisionSentAt()).as("nothing to resend for a deleted document").isNotNull();
    }
}
