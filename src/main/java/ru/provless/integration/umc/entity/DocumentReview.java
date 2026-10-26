package ru.provless.umc.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(schema = "umc", name = "document_review")
@Getter
@Setter
public class DocumentReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false, unique = true)
    private UUID documentId;

    /** Needed to re-fetch the file later (amoCRM link) — not in the original plan's data model. */
    @Column(name = "file_id", nullable = false)
    private UUID fileId;

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "document_type", nullable = false)
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private DocumentReviewStatus status = DocumentReviewStatus.RECEIVED;

    @Column(name = "ocr_raw_text", columnDefinition = "text")
    private String ocrRawText;

    @Column(name = "ocr_confidence", precision = 4, scale = 3)
    private BigDecimal ocrConfidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ocr_matched_keywords", columnDefinition = "jsonb")
    private List<String> ocrMatchedKeywords;

    @Column(name = "amocrm_lead_id")
    private Long amocrmLeadId;

    @Column(name = "amocrm_contact_id")
    private Long amocrmContactId;

    /** Embedded in the file link shown in the amoCRM card — see FileAccessController. */
    @Column(name = "file_access_token", nullable = false, unique = true)
    private UUID fileAccessToken = UUID.randomUUID();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
