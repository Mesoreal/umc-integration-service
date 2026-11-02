package ru.provless.integration.manager;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Who assigned whom and when (plan.md §6). {@code agentId} null — the manager was taken off. Kept after account deletion. */
@Entity
@Table(schema = "chatwoot", name = "personal_manager_history")
@Getter
@NoArgsConstructor
public class PersonalManagerHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "directus_id", nullable = false)
    private UUID directusId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "agent_name")
    private String agentName;

    @Column(name = "assigned_by")
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;

    @Column(name = "recorded_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime recordedAt;

    public PersonalManagerHistory(UUID directusId, UUID userId, Long agentId, String agentName, String assignedBy,
                                  OffsetDateTime assignedAt) {
        this.directusId = directusId;
        this.userId = userId;
        this.agentId = agentId;
        this.agentName = agentName;
        this.assignedBy = assignedBy;
        this.assignedAt = assignedAt;
    }
}
