package ru.provless.integration.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/** The working hours of an inbox as last reported (a hash), so only a change is sent. */
@Entity
@Table(schema = "chatwoot", name = "chat_inbox_hours")
@Getter
@Setter
@NoArgsConstructor
public class ChatInboxHours {

    @Id
    @Column(name = "inbox_id")
    private long inboxId;

    @Column(name = "hours_hash", nullable = false)
    private String hoursHash;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public ChatInboxHours(long inboxId, String hoursHash) {
        this.inboxId = inboxId;
        this.hoursHash = hoursHash;
    }
}
