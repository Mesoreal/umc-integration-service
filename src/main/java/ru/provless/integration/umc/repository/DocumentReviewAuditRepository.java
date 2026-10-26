package ru.provless.integration.umc.repository;

import ru.provless.integration.umc.entity.DocumentReviewAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DocumentReviewAuditRepository extends JpaRepository<DocumentReviewAudit, UUID> {
}
