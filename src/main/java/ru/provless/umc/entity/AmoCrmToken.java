package ru.provless.umc.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * Single-row table (id is always 1) holding the live amoCRM OAuth2 tokens. amoCRM
 * rotates the refresh token on every use, so it must be persisted here, not just held
 * in the {@code AMOCRM_REFRESH_TOKEN} env var (which only seeds the first row).
 */
@Entity
@Table(schema = "umc", name = "amocrm_token")
@Getter
@Setter
public class AmoCrmToken {

    @Id
    private short id = 1;

    @Column(name = "access_token", columnDefinition = "text")
    private String accessToken;

    @Column(name = "refresh_token", columnDefinition = "text", nullable = false)
    private String refreshToken;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
