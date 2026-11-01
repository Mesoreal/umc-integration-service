package ru.provless.integration.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** An agent's presence as last reported to chat-service; {@code lastSeenAt} — the last poll that saw them online. */
@Entity
@Table(schema = "chatwoot", name = "chat_agent")
@Getter
@Setter
@NoArgsConstructor
public class ChatAgent {

    @Id
    @Column(name = "agent_id")
    private long agentId;

    @Column(nullable = false)
    private String availability;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    public ChatAgent(long agentId, String availability) {
        this.agentId = agentId;
        this.availability = availability;
    }
}
