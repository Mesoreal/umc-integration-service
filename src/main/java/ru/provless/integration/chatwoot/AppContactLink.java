package ru.provless.integration.chatwoot;

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

/** Which Chatwoot contact an app user is attached to. Chatwoot stays the source of truth; this is the fast path. */
@Entity
@Table(schema = "chatwoot", name = "app_contact_link")
@Getter
@Setter
@NoArgsConstructor
public class AppContactLink {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "contact_id", nullable = false)
    private long contactId;

    @CreationTimestamp
    @Column(name = "linked_at", nullable = false, updatable = false)
    private OffsetDateTime linkedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public AppContactLink(UUID userId, long contactId) {
        this.userId = userId;
        this.contactId = contactId;
    }
}
