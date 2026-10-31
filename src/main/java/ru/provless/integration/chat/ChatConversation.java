package ru.provless.integration.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A Chatwoot conversation of an app user; {@code current} — the one the user's messages go to now. */
@Entity
@Table(schema = "chatwoot", name = "chat_conversation")
@Getter
@Setter
@NoArgsConstructor
public class ChatConversation {

    /** Chatwoot display_id. */
    @Id
    @Column(name = "conversation_id")
    private long conversationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "contact_id", nullable = false)
    private long contactId;

    @Column(name = "inbox_id", nullable = false)
    private long inboxId;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private boolean current;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "agent_last_seen_at")
    private OffsetDateTime agentLastSeenAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public ChatConversation(long conversationId, UUID userId, long contactId, long inboxId, String status) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.contactId = contactId;
        this.inboxId = inboxId;
        this.status = status;
        this.current = true;
    }
}
