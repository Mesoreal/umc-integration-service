package ru.provless.integration.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A chat message that exists on both sides: chat-service id ↔ Chatwoot id. */
@Entity
@Table(schema = "chatwoot", name = "chat_message_link")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessageLink {

    public static final String CLIENT = "client";
    public static final String MANAGER = "manager";

    @Id
    @Column(name = "cw_message_id")
    private long cwMessageId;

    /** chat-service id; null for a manager's message until chat-service has it (it keys them by external_id). */
    @Column(name = "message_id", unique = true)
    private UUID messageId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "conversation_id", nullable = false)
    private long conversationId;

    @Column(nullable = false)
    private String direction;

    @Column(name = "cw_created_at", nullable = false)
    private OffsetDateTime cwCreatedAt;

    @Column(name = "read_by_manager", nullable = false)
    private boolean readByManager;

    @Column(name = "delivered_to_client", nullable = false)
    private boolean deliveredToClient;

    public ChatMessageLink(long cwMessageId, UUID messageId, UUID userId, long conversationId, String direction,
                           OffsetDateTime cwCreatedAt) {
        this.cwMessageId = cwMessageId;
        this.messageId = messageId;
        this.userId = userId;
        this.conversationId = conversationId;
        this.direction = direction;
        this.cwCreatedAt = cwCreatedAt;
    }

    public String externalId() {
        return externalId(cwMessageId);
    }

    /** How chat-service knows a Chatwoot message: {@code cw:<id>}. */
    public static String externalId(long cwMessageId) {
        return "cw:" + cwMessageId;
    }

    /** @return the Chatwoot id of {@code cw:<id>}, null for anything else */
    public static Long cwId(String externalId) {
        if (externalId == null || !externalId.startsWith("cw:")) {
            return null;
        }
        try {
            return Long.parseLong(externalId.substring(3));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
