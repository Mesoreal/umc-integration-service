package ru.provless.integration.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatMessageLinkRepository extends JpaRepository<ChatMessageLink, Long> {

    Optional<ChatMessageLink> findByMessageId(UUID messageId);

    /** Managers' messages of the user up to {@code upTo} that Chatwoot does not show as delivered yet, oldest first. */
    @Query("""
            select l from ChatMessageLink l
            where l.userId = :userId and l.direction = 'manager' and not l.deliveredToClient and l.cwMessageId <= :upTo
            order by l.cwMessageId
            """)
    List<ChatMessageLink> undeliveredUpTo(@Param("userId") UUID userId, @Param("upTo") long upTo);
}
