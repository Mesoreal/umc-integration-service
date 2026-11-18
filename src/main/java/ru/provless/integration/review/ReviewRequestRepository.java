package ru.provless.integration.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRequestRepository extends JpaRepository<ReviewRequest, UUID> {

    Optional<ReviewRequest> findByKindAndSubjectId(RejectionReasons.Kind kind, UUID subjectId);

    Optional<ReviewRequest> findFirstByConversationIdOrderByCreatedAtDesc(long conversationId);

    List<ReviewRequest> findTop50ByStatusOrderByCreatedAtAsc(ReviewRequest.Status status);
}
