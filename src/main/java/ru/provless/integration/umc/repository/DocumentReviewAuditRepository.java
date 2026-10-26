package ru.provless.umc.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.provless.umc.entity.DocumentReviewAudit;

import java.util.UUID;

public interface DocumentReviewAuditRepository extends JpaRepository<DocumentReviewAudit, UUID> {
}
