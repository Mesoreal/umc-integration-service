package ru.provless.integration.manager;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A row of Directus «client_managers» as the service last saw it: whose (null — the phone has no account yet) and which
 * agent (null — no manager in the row), and who chose that agent. {@code notified} — the client's chat header and Chatwoot
 * contact show it; false — to be sent on the next poll.
 */
@Entity
@Table(schema = "chatwoot", name = "personal_manager")
@Getter
@Setter
@NoArgsConstructor
public class PersonalManager {

    @Id
    @Column(name = "directus_id")
    private UUID directusId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "agent_name")
    private String agentName;

    @Column(name = "assigned_by")
    private String assignedBy;

    @Column(name = "assigned_at")
    private OffsetDateTime assignedAt;

    @Column(nullable = false)
    private boolean notified;

    public PersonalManager(UUID directusId) {
        this.directusId = directusId;
    }
}
