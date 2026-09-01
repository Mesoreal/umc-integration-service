package ru.provless.umc.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.provless.umc.entity.DocumentReview;

import java.util.Optional;
import java.util.UUID;

public interface DocumentReviewRepository extends JpaRepository<DocumentReview, UUID> {

    Optional<DocumentReview> findByDocumentId(UUID documentId);
}
