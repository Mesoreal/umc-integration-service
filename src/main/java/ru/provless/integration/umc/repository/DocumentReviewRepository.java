package ru.provless.integration.umc.repository;

import ru.provless.integration.umc.entity.DocumentReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentReviewRepository extends JpaRepository<DocumentReview, UUID> {

    Optional<DocumentReview> findByDocumentId(UUID documentId);

    Optional<DocumentReview> findByAmocrmLeadId(Long amocrmLeadId);
}
