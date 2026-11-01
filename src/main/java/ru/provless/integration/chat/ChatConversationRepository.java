package ru.provless.integration.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import java.util.Optional;
import java.util.UUID;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    Optional<ChatConversation> findByUserIdAndCurrentTrue(UUID userId);

    /** Users whose current request is in {@code inboxId}: they get that inbox's working hours. */
    @Query("select c.userId from ChatConversation c where c.current and c.inboxId = :inboxId")
    List<UUID> currentUsersOfInbox(@Param("inboxId") long inboxId);

    /** Only this column: the read poll must not overwrite status or assignee written meanwhile by the listeners. */
    @Modifying
    @Transactional
    @Query("update ChatConversation c set c.agentLastSeenAt = :seenAt where c.conversationId = :id")
    void setAgentLastSeenAt(@Param("id") long conversationId, @Param("seenAt") OffsetDateTime seenAt);
}
