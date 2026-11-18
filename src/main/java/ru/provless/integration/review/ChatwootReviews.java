package ru.provless.integration.review;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.provless.integration.chatwoot.AppContactService;
import ru.provless.integration.chatwoot.ChatwootClient;
import ru.provless.integration.chatwoot.ChatwootConversation;
import ru.provless.integration.chatwoot.ChatwootUpload;
import ru.provless.integration.profile.ContactInfo;
import ru.provless.integration.profile.FileContent;
import ru.provless.integration.profile.ProfileServiceClient;
import ru.provless.integration.umc.entity.DocumentReview;
import ru.provless.integration.umc.entity.DocumentReviewStatus;
import ru.provless.integration.umc.repository.DocumentReviewRepository;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Requests of the Chatwoot inbox «Проверка документов» (front requests S5 §10): a document of an organization's profile or a LEGAL /
 * IE payer. A request is a conversation of the client's contact in that inbox with a note — what to check, the DaData suggestion and
 * where it differs from what the client typed — and the files; the decision is taken in the panel tab «Проверка документов»
 * ({@link ReviewPanelController}) and goes to profile-service.
 *
 * <p>A request is saved first (QUEUED) and put into Chatwoot right away or by the retry pass — Chatwoot off or failing loses nothing.
 */
@Slf4j
@Service
public class ChatwootReviews {

    static final int MAX_ERROR = 1000;

    private final ReviewRequestRepository requests;
    private final DocumentReviewRepository documentReviews;
    private final ProfileServiceClient profiles;
    private final AppContactService contacts;
    private final ChatwootClient chatwoot;
    private final ReviewInbox inbox;
    private final RejectionReasons reasons;
    private final Clock clock = Clock.systemUTC();
    private final boolean enabled;

    public ChatwootReviews(ReviewRequestRepository requests, DocumentReviewRepository documentReviews, ProfileServiceClient profiles,
                           AppContactService contacts, ChatwootClient chatwoot, ReviewInbox inbox, RejectionReasons reasons,
                           @Value("${chatwoot.enabled:false}") boolean enabled) {
        this.requests = requests;
        this.documentReviews = documentReviews;
        this.profiles = profiles;
        this.contacts = contacts;
        this.chatwoot = chatwoot;
        this.inbox = inbox;
        this.reasons = reasons;
        this.enabled = enabled;
    }

    /** A new request (once per document / payer) — into Chatwoot now if it is on, else by the retry pass. */
    public ReviewRequest open(RejectionReasons.Kind kind, UUID subjectId, UUID userId, UUID documentReviewId) {
        ReviewRequest request = requests.findByKindAndSubjectId(kind, subjectId)
                .orElseGet(() -> requests.save(new ReviewRequest(kind, subjectId, userId, documentReviewId)));
        log.info("Review request {} ({} {}) of userId={}: {}", request.getId(), kind, subjectId, userId, request.getStatus());
        if (enabled && request.getStatus() == ReviewRequest.Status.QUEUED) {
            deliver(request);
        }
        return request;
    }

    @Scheduled(initialDelayString = "${review.retry-initial-delay:PT1M}", fixedDelayString = "${review.retry-interval:PT5M}")
    public void deliverQueued() {
        if (!enabled) {
            return;
        }
        requests.findTop50ByStatusOrderByCreatedAtAsc(ReviewRequest.Status.QUEUED).forEach(this::deliver);
    }

    /** Creates the conversation once (its id is saved before the notes, so a retry does not open a second one). */
    void deliver(ReviewRequest request) {
        try {
            Optional<Note> note = note(request);
            if (note.isEmpty()) {
                request.setStatus(ReviewRequest.Status.CANCELLED);
                request.setLastError("deleted by the client before the check");
                requests.save(request);
                log.info("Review request {}: the {} is gone, cancelled", request.getId(), request.getKind());
                return;
            }
            if (request.getConversationId() == null) {
                long contactId = contacts.ensure(request.getUserId(), null);
                long inboxId = inbox.id();
                String sourceId = request.getUserId().toString();
                chatwoot.addContactInbox(contactId, inboxId, sourceId);
                ChatwootConversation conversation = chatwoot.createConversation(inboxId, contactId, sourceId,
                        Map.of("review_request_id", request.getId().toString(), "review_kind", request.getKind().name()));
                request.setConversationId(conversation.id());
                requests.save(request);
            }
            chatwoot.createPrivateNote(request.getConversationId(), note.get().text(), null);
            if (!note.get().files().isEmpty()) {
                chatwoot.createPrivateNoteWithFiles(request.getConversationId(), "Файлы на проверку", note.get().files());
            }
            request.setStatus(ReviewRequest.Status.OPEN);
            request.setLastError(null);
            requests.save(request);
            log.info("Review request {} opened in Chatwoot conversation {}", request.getId(), request.getConversationId());
        } catch (RuntimeException e) {
            request.setAttempts(request.getAttempts() + 1);
            request.setLastError(truncate(String.valueOf(e.getMessage()), MAX_ERROR));
            requests.save(request);
            log.warn("Review request {} not put into Chatwoot (attempt {}), next pass retries: {}", request.getId(),
                    request.getAttempts(), e.getMessage());
        }
    }

    record Note(String text, List<ChatwootUpload> files) {
    }

    /** Empty — the document / payer is gone (the client deleted it). */
    Optional<Note> note(ReviewRequest request) {
        ContactInfo contact = profiles.findContactInfo(request.getUserId()).orElse(new ContactInfo(null, null));
        String client = "Клиент: " + orDash(contact.fullName()) + ", " + orDash(contact.phone());
        if (request.getKind() == RejectionReasons.Kind.PAYER) {
            Optional<JsonNode> payer = profiles.findOrganization(request.getUserId(), request.getSubjectId());
            if (payer.isEmpty()) {
                return Optional.empty();
            }
            List<ChatwootUpload> files = new ArrayList<>();
            String licenseFile = payer.get().path("license_file_id").asText(null);
            if (licenseFile != null && !licenseFile.isBlank()) {
                files.add(upload("Лицензия", profiles.fetchFile(request.getUserId(), UUID.fromString(licenseFile))));
            }
            return Optional.of(new Note(PayerNote.of(payer.get(), client), files));
        }
        DocumentReview review = request.getDocumentReviewId() == null ? null
                : documentReviews.findById(request.getDocumentReviewId()).orElse(null);
        if (review == null) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder("Документ на проверку: ").append(DocumentLabels.of(review.getDocumentType()))
                .append(" (профиль организации)\n").append(client);
        if (review.getOcrConfidence() != null || review.getOcrMatchedKeywords() != null) {
            text.append("\nOCR: уверенность ").append(review.getOcrConfidence() == null ? "—" : review.getOcrConfidence())
                    .append(review.getOcrMatchedKeywords() == null || review.getOcrMatchedKeywords().isEmpty() ? ""
                            : ", найдено: " + String.join(", ", review.getOcrMatchedKeywords()));
        }
        text.append("\n\nРешение — во вкладке «Проверка документов».");
        FileContent file = profiles.fetchFile(review.getUserId(), review.getFileId());
        return Optional.of(new Note(text.toString(), List.of(upload(DocumentLabels.of(review.getDocumentType()), file))));
    }

    // ─── the decision (the panel) ───────────────────────────────────────────

    /** The panel refused the decision: {@code status} is the HTTP answer to the page. */
    public static class Refused extends RuntimeException {
        final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public Optional<ReviewRequest> ofConversation(long conversationId) {
        return requests.findFirstByConversationIdOrderByCreatedAtDesc(conversationId);
    }

    public ReviewRequest decide(UUID requestId, boolean approved, String reasonCode, String comment, String summary, String agent) {
        ReviewRequest request = requests.findById(requestId).orElseThrow(() -> new Refused(404, "Заявка не найдена"));
        if (request.getStatus() != ReviewRequest.Status.OPEN) {
            throw new Refused(409, request.getStatus() == ReviewRequest.Status.DECIDED ? "Решение уже принято"
                    : "Заявки больше нет: клиент удалил " + (request.getKind() == RejectionReasons.Kind.PAYER ? "плательщика" : "документ"));
        }
        ProfileServiceClient.Decision decision;
        String noteText;
        if (approved) {
            decision = ProfileServiceClient.Decision.approve(request.getKind() == RejectionReasons.Kind.DOCUMENT ? blankToNull(summary) : null);
            noteText = agent + ": одобрено";
        } else {
            RejectionReasons.Reason reason = reasons.byCode(request.getKind(), reasonCode)
                    .orElseThrow(() -> new Refused(400, "Выберите причину из списка"));
            if (RejectionReasons.OTHER.equals(reason.code()) && blankToNull(comment) == null) {
                throw new Refused(400, "Для «Другая причина» нужен комментарий");
            }
            decision = ProfileServiceClient.Decision.reject(reason.code(), reason.text(), blankToNull(comment));
            noteText = agent + ": отклонено — " + reason.text() + (blankToNull(comment) == null ? "" : "\nКомментарий: " + comment.trim());
        }
        try {
            if (request.getKind() == RejectionReasons.Kind.PAYER) {
                profiles.decideOrganization(request.getUserId(), request.getSubjectId(), decision);
            } else {
                profiles.decideDocument(request.getUserId(), request.getSubjectId(), decision);
            }
        } catch (ProfileServiceClient.DecisionRefused e) {
            if (e.gone()) {
                request.setStatus(ReviewRequest.Status.CANCELLED);
                requests.save(request);
                note(request.getConversationId(), "Клиент удалил " + (request.getKind() == RejectionReasons.Kind.PAYER ? "плательщика" : "документ")
                        + " — решение не нужно");
                resolve(request.getConversationId());
                throw new Refused(409, "Заявки больше нет: клиент удалил "
                        + (request.getKind() == RejectionReasons.Kind.PAYER ? "плательщика" : "документ"));
            }
            throw new Refused(400, "profile-service не принял решение: " + e.getMessage());
        }

        request.setStatus(ReviewRequest.Status.DECIDED);
        request.setDecision(approved ? "APPROVED" : "REJECTED");
        request.setDecidedBy(agent);
        request.setDecidedAt(OffsetDateTime.now(clock));
        requests.save(request);
        if (request.getDocumentReviewId() != null) {
            documentReviews.findById(request.getDocumentReviewId()).ifPresent(review -> {
                review.setStatus(approved ? DocumentReviewStatus.APPROVED : DocumentReviewStatus.REJECTED);
                review.setDecisionSentAt(OffsetDateTime.now(clock));
                documentReviews.save(review);
            });
        }
        note(request.getConversationId(), noteText);
        resolve(request.getConversationId());
        log.info("Review request {} decided by {}: {}", request.getId(), agent, request.getDecision());
        return request;
    }

    /** For the history; the decision is made already, a failed note or status only logged. */
    private void note(Long conversationId, String text) {
        try {
            chatwoot.createPrivateNote(conversationId, text, null);
        } catch (RuntimeException e) {
            log.warn("Review note not posted to conversation {}: {}", conversationId, e.getMessage());
        }
    }

    private void resolve(Long conversationId) {
        try {
            chatwoot.setConversationStatus(conversationId, "resolved");
        } catch (RuntimeException e) {
            log.warn("Review conversation {} not resolved: {}", conversationId, e.getMessage());
        }
    }

    private static ChatwootUpload upload(String label, FileContent file) {
        String type = file.contentType() == null ? "application/octet-stream" : file.contentType();
        String ext = type.contains("pdf") ? ".pdf" : type.contains("png") ? ".png" : type.contains("jpeg") || type.contains("jpg") ? ".jpg"
                : type.contains("heic") ? ".heic" : "";
        return new ChatwootUpload(label + ext, type, file.data());
    }

    static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
