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

public interface ChatMessageLinkRepository extends JpaRepository<ChatMessageLink, Long> {

    Optional<ChatMessageLink> findByMessageId(UUID messageId);

    /** By the id chat-service knows (external_id {@code cw:<id>}), wherever the message lives now. */
    Optional<ChatMessageLink> findByExternalCwId(long externalCwId);

    List<ChatMessageLink> findByConversationId(long conversationId);

    /** A transfer moved the message: the link now points at its copy in the new conversation. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE chatwoot.chat_message_link SET cw_message_id = :newId, conversation_id = :conversationId, cw_created_at = :at
            WHERE cw_message_id = :oldId""", nativeQuery = true)
    int repoint(@Param("oldId") long oldId, @Param("newId") long newId, @Param("conversationId") long conversationId,
                @Param("at") OffsetDateTime at);

    /** Managers' messages of the user up to {@code upTo} that Chatwoot does not show as delivered yet, oldest first. */
    @Query("""
            select l from ChatMessageLink l
            where l.userId = :userId and l.direction = 'manager' and not l.deliveredToClient and l.externalCwId <= :upTo
            order by l.externalCwId
            """)
    List<ChatMessageLink> undeliveredUpTo(@Param("userId") UUID userId, @Param("upTo") long upTo);

    /** Conversations with user messages no agent has seen yet: the read poll looks at these only. */
    @Query("select distinct l.conversationId from ChatMessageLink l where l.direction = 'client' and not l.readByManager")
    List<Long> conversationsAwaitingRead();

    /** Marks the user's messages up to {@code seenAt} read; how many it marked. */
    @Modifying
    @Transactional
    @Query("""
            update ChatMessageLink l set l.readByManager = true
            where l.conversationId = :conversationId and l.direction = 'client' and not l.readByManager
              and l.cwCreatedAt <= :seenAt
            """)
    int markReadUpTo(@Param("conversationId") long conversationId, @Param("seenAt") OffsetDateTime seenAt);
}
