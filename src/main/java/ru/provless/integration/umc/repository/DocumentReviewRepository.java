package ru.provless.integration.umc.repository;

import ru.provless.integration.umc.entity.DocumentReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentReviewRepository extends JpaRepository<DocumentReview, UUID> {

    Optional<DocumentReview> findByDocumentId(UUID documentId);

    Optional<DocumentReview> findByAmocrmLeadId(Long amocrmLeadId);

    /** Decided in amoCRM, not yet taken by profile-service (front requests S5 §10). */
    java.util.List<DocumentReview> findTop50ByStatusInAndDecisionSentAtIsNullAndAmocrmLeadIdIsNotNull(
            java.util.Collection<ru.provless.integration.umc.entity.DocumentReviewStatus> statuses);
}
