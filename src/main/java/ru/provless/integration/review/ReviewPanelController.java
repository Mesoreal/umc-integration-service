package ru.provless.integration.review;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.provless.integration.umc.entity.DocumentType;
import ru.provless.integration.umc.repository.DocumentReviewRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The panel tab «Проверка документов» (front requests S5 §10): the request of the open conversation and the decision on it. The API
 * is under {@code /manager-panel/api/} — the panel's key ({@code ManagerPanelKeyFilter}), like «Заказы клиента».
 */
@RestController
public class ReviewPanelController {

    private static final ClassPathResource PAGE = new ClassPathResource("manager-panel/reviews.html");

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DecisionRequest(Boolean approved, String reason, String comment, String summary, String agentName) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReviewView(UUID id, String kind, String status, String title, boolean summaryAllowed,
                             List<RejectionReasons.Reason> reasons, String decision, String decidedBy, OffsetDateTime decidedAt) {
    }

    private final ChatwootReviews reviews;
    private final RejectionReasons reasons;
    private final DocumentReviewRepository documentReviews;

    public ReviewPanelController(ChatwootReviews reviews, RejectionReasons reasons, DocumentReviewRepository documentReviews) {
        this.reviews = reviews;
        this.reasons = reasons;
        this.documentReviews = documentReviews;
    }

    @GetMapping("/manager-panel/reviews")
    public ResponseEntity<Resource> page() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore()).body(PAGE);
    }

    /** 404 {@code NO_REVIEW} — the conversation is not a request of «Проверка документов». */
    @GetMapping("/manager-panel/api/reviews")
    public ResponseEntity<?> ofConversation(@RequestParam("conversation_id") long conversationId) {
        return reviews.ofConversation(conversationId).<ResponseEntity<?>>map(r -> ResponseEntity.ok(view(r)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error_code", "NO_REVIEW",
                        "message", "В этом разговоре нет заявки на проверку")));
    }

    @PostMapping("/manager-panel/api/reviews/{id}/decision")
    public ReviewView decide(@PathVariable("id") UUID id, @RequestBody DecisionRequest body) {
        if (body.approved() == null) {
            throw new ChatwootReviews.Refused(400, "approved is required");
        }
        String agent = body.agentName() == null || body.agentName().isBlank() ? "Менеджер" : body.agentName().trim();
        return view(reviews.decide(id, body.approved(), body.reason(), body.comment(), body.summary(), agent));
    }

    @ExceptionHandler(ChatwootReviews.Refused.class)
    public ResponseEntity<Map<String, String>> refused(ChatwootReviews.Refused e) {
        return ResponseEntity.status(e.status).body(Map.of("error_code", "REVIEW_REFUSED", "message", e.getMessage()));
    }

    private ReviewView view(ReviewRequest r) {
        DocumentType type = r.getDocumentReviewId() == null ? null
                : documentReviews.findById(r.getDocumentReviewId()).map(d -> d.getDocumentType()).orElse(null);
        String title = r.getKind() == RejectionReasons.Kind.PAYER ? "Плательщик"
                : type == null ? "Документ" : DocumentLabels.of(type);
        return new ReviewView(r.getId(), r.getKind().name(), r.getStatus().name(), title, type == DocumentType.DIPLOMA,
                r.getStatus() == ReviewRequest.Status.OPEN ? reasons.offered(r.getKind()) : List.of(),
                r.getDecision(), r.getDecidedBy(), r.getDecidedAt());
    }
}
