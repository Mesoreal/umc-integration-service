package ru.provless.integration.amocrm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** The end date of the long-lived token is read from the token itself (its JWT {@code exp}) — for the warning 30 days ahead. */
class AmoCrmTokenTermTest {

    @Test
    void theExpOfTheJwt_orNullForAnythingElse() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"aud\":\"x\",\"exp\":1893456000}".getBytes(StandardCharsets.UTF_8));
        assertThat(AmoCrmClient.expiresAt("eyJhbGciOiJSUzI1NiJ9." + payload + ".sig")).isEqualTo(Instant.ofEpochSecond(1893456000));

        assertThat(AmoCrmClient.expiresAt(null)).isNull();
        assertThat(AmoCrmClient.expiresAt("")).isNull();
        assertThat(AmoCrmClient.expiresAt("not-a-jwt")).isNull();
        assertThat(AmoCrmClient.expiresAt("a.%%%.c")).isNull();
        String noExp = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"aud\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(AmoCrmClient.expiresAt("h." + noExp + ".s")).isNull();
    }
}
