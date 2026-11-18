package ru.provless.integration.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A request in the Chatwoot inbox «Проверка документов» (front requests S5 §10): a document of an organization or a payer. */
@Entity
@Table(schema = "umc", name = "review_request")
@Getter
@Setter
@NoArgsConstructor
public class ReviewRequest {

    public enum Status { QUEUED, OPEN, DECIDED, CANCELLED }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RejectionReasons.Kind kind;

    /** The document id (DOCUMENT) or the payer's organization id (PAYER) in profile-service. */
    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "document_review_id")
    private UUID documentReviewId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.QUEUED;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    /** APPROVED / REJECTED. */
    @Column(length = 20)
    private String decision;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public ReviewRequest(RejectionReasons.Kind kind, UUID subjectId, UUID userId, UUID documentReviewId) {
        this.id = UUID.randomUUID();
        this.kind = kind;
        this.subjectId = subjectId;
        this.userId = userId;
        this.documentReviewId = documentReviewId;
    }
}
