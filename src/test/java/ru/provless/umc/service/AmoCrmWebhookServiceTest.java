package ru.provless.umc.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import ru.provless.umc.entity.DocumentReview;
import ru.provless.umc.entity.DocumentReviewAuditEventType;
import ru.provless.umc.entity.DocumentReviewStatus;
import ru.provless.umc.repository.DocumentReviewRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AmoCrmWebhookServiceTest {

    private static final long LEAD_ID = 555L;
    private static final long APPROVED_STAGE_ID = 100L;
    private static final long REJECTED_STAGE_ID = 200L;
    private static final long OTHER_STAGE_ID = 300L;

    @Mock
    private DocumentReviewRepository documentReviewRepository;
    @Mock
    private DocumentReviewAuditWriter auditWriter;

    private AmoCrmWebhookService service;
    private DocumentReview review;

    @BeforeEach
    void setUp() {
        service = new AmoCrmWebhookService(documentReviewRepository, auditWriter);
        ReflectionTestUtils.setField(service, "stageApprovedIdRaw", String.valueOf(APPROVED_STAGE_ID));
        ReflectionTestUtils.setField(service, "stageRejectedIdRaw", String.valueOf(REJECTED_STAGE_ID));

        review = new DocumentReview();
        review.setId(UUID.randomUUID());
        review.setAmocrmLeadId(LEAD_ID);
        review.setStatus(DocumentReviewStatus.PENDING_MANAGER);
    }

    @Test
    void movesReviewToApprovedOnMatchingStage() {
        when(documentReviewRepository.findByAmocrmLeadId(LEAD_ID)).thenReturn(Optional.of(review));

        service.handle(leadStatusForm(LEAD_ID, APPROVED_STAGE_ID, "42"));

        assertThat(review.getStatus()).isEqualTo(DocumentReviewStatus.APPROVED);
        verify(documentReviewRepository).save(review);

        ArgumentCaptor<String> actorCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditWriter).write(eq(review), eq(DocumentReviewAuditEventType.CRM_STATUS_CHANGED), any(), actorCaptor.capture());
        assertThat(actorCaptor.getValue()).isEqualTo("amocrm_user:42");
    }

    @Test
    void movesReviewToRejectedOnMatchingStage() {
        when(documentReviewRepository.findByAmocrmLeadId(LEAD_ID)).thenReturn(Optional.of(review));

        service.handle(leadStatusForm(LEAD_ID, REJECTED_STAGE_ID, null));

        assertThat(review.getStatus()).isEqualTo(DocumentReviewStatus.REJECTED);
    }

    @Test
    void ignoresUnrelatedStageButStillAudits() {
        when(documentReviewRepository.findByAmocrmLeadId(LEAD_ID)).thenReturn(Optional.of(review));

        service.handle(leadStatusForm(LEAD_ID, OTHER_STAGE_ID, null));

        assertThat(review.getStatus()).isEqualTo(DocumentReviewStatus.PENDING_MANAGER);
        verify(documentReviewRepository, never()).save(any());
        verify(auditWriter).write(eq(review), eq(DocumentReviewAuditEventType.CRM_STATUS_CHANGED),
                eq(Map.of("statusId", OTHER_STAGE_ID, "ignored", true)));
    }

    @Test
    void ignoresWebhookForUnknownLead() {
        when(documentReviewRepository.findByAmocrmLeadId(999L)).thenReturn(Optional.empty());

        service.handle(leadStatusForm(999L, APPROVED_STAGE_ID, null));

        verify(documentReviewRepository, never()).save(any());
        verify(auditWriter, never()).write(any(), any(), any());
    }

    @Test
    void duplicateDeliveryToSameStatusIsIdempotent() {
        review.setStatus(DocumentReviewStatus.APPROVED);
        when(documentReviewRepository.findByAmocrmLeadId(LEAD_ID)).thenReturn(Optional.of(review));

        service.handle(leadStatusForm(LEAD_ID, APPROVED_STAGE_ID, null));

        verify(documentReviewRepository, never()).save(any());
        verify(auditWriter, never()).write(any(), any(), any());
    }

    @Test
    void refusesToOverwriteATerminalStatus() {
        review.setStatus(DocumentReviewStatus.APPROVED);
        when(documentReviewRepository.findByAmocrmLeadId(LEAD_ID)).thenReturn(Optional.of(review));

        service.handle(leadStatusForm(LEAD_ID, REJECTED_STAGE_ID, null));

        assertThat(review.getStatus()).isEqualTo(DocumentReviewStatus.APPROVED);
        verify(documentReviewRepository, never()).save(any());
        verify(auditWriter).write(any(), eq(DocumentReviewAuditEventType.CRM_STATUS_CHANGED), any());
    }

    @Test
    void parsesMultipleLeadsInOneCallback() {
        DocumentReview other = new DocumentReview();
        other.setId(UUID.randomUUID());
        other.setAmocrmLeadId(777L);
        other.setStatus(DocumentReviewStatus.PENDING_MANAGER);

        when(documentReviewRepository.findByAmocrmLeadId(LEAD_ID)).thenReturn(Optional.of(review));
        when(documentReviewRepository.findByAmocrmLeadId(777L)).thenReturn(Optional.of(other));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("leads[status][0][id]", String.valueOf(LEAD_ID));
        form.add("leads[status][0][status_id]", String.valueOf(APPROVED_STAGE_ID));
        form.add("leads[status][1][id]", "777");
        form.add("leads[status][1][status_id]", String.valueOf(REJECTED_STAGE_ID));

        service.handle(form);

        assertThat(review.getStatus()).isEqualTo(DocumentReviewStatus.APPROVED);
        assertThat(other.getStatus()).isEqualTo(DocumentReviewStatus.REJECTED);
    }

    private MultiValueMap<String, String> leadStatusForm(long leadId, long statusId, String modifiedUserId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("leads[status][0][id]", String.valueOf(leadId));
        form.add("leads[status][0][status_id]", String.valueOf(statusId));
        form.add("leads[status][0][pipeline_id]", "1300271");
        if (modifiedUserId != null) {
            form.add("leads[status][0][modified_user_id]", modifiedUserId);
        }
        return form;
    }
}
