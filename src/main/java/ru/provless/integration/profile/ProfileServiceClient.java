package ru.provless.integration.profile;

import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Fetches uploaded-file bytes from profile-service's internal API — the "internal API
 * profile-service" referenced in the plan for stage 3 (OCR и фильтр). Authenticates with
 * the shared {@code INTERNAL_API_KEY} via {@code X-Internal-Auth}, same as
 * auth-service ↔ phone-auth.
 */
@Slf4j
@Component
public class ProfileServiceClient {

    @Value("${profile-service.base-url}")
    private String baseUrl;

    @Value("${profile-service.internal-api-key}")
    private String internalApiKey;

    @Value("${profile-service.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${profile-service.read-timeout-ms:15000}")
    private int readTimeoutMs;

    private RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Internal-Auth", internalApiKey)
                .defaultHeader("User-Agent", "integration-service")
                .build();

        log.info("ProfileServiceClient initialized: baseUrl={} connectTimeoutMs={} readTimeoutMs={}",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /** Exactly these users are graduates now (contract §17.1): profile-service replaces the previous list. */
    public void replaceGraduates(java.util.Collection<UUID> userIds) {
        restClient.put().uri("/internal/profiles/graduates").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("user_ids", userIds)).retrieve().toBodilessEntity();
    }

    public FileContent fetchFile(UUID userId, UUID fileId) {
        try {
            ResponseEntity<byte[]> response = restClient.get()
                    .uri("/internal/storage/{userId}/{fileId}", userId, fileId)
                    .retrieve()
                    .toEntity(byte[].class);

            byte[] body = response.getBody();
            if (body == null) {
                throw new ProfileFileFetchException("profile-service returned an empty body for fileId=" + fileId, null);
            }
            String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            return new FileContent(body, contentType);
        } catch (ProfileFileFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new ProfileFileFetchException("Failed to fetch fileId=" + fileId + " from profile-service", e);
        }
    }

    /** Used to find/create the client's amoCRM contact (stage 6 — see the plan). */
    public ContactInfo fetchContactInfo(UUID userId) {
        try {
            String responseBody = restClient.get()
                    .uri("/internal/profiles/{userId}/contact-info", userId)
                    .retrieve()
                    .body(String.class);

            var json = jsonMapper.readTree(responseBody);
            return new ContactInfo(json.path("phone").asText(null), json.path("fullName").asText(null));
        } catch (Exception e) {
            throw new ProfileContactFetchException("Failed to fetch contact info for userId=" + userId + " from profile-service", e);
        }
    }

    /** Like {@link #fetchContactInfo}, but empty when the profile does not exist yet (it is created lazily). */
    public Optional<ContactInfo> findContactInfo(UUID userId) {
        try {
            return Optional.of(fetchContactInfo(userId));
        } catch (ProfileContactFetchException e) {
            if (e.getCause() instanceof HttpClientErrorException.NotFound) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // ─── checks of documents and payers (front requests S5 §10) ────────────

    /** A decision for profile-service's internal API (snake_case there). {@code reason} null for an approval. */
    public record Decision(boolean approved, String reason, String reasonText, String comment, String summary) {

        public static Decision approve(String summary) {
            return new Decision(true, null, null, null, summary);
        }

        public static Decision reject(String reason, String reasonText, String comment) {
            return new Decision(false, reason, reasonText, comment, null);
        }

        java.util.Map<String, Object> body() {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("approved", approved);
            body.put("reason", reason);
            body.put("reason_text", reasonText);
            body.put("comment", comment);
            body.put("summary", summary);
            return body;
        }
    }

    /** profile-service refused the decision: 404 — the document / payer is gone (deleted by the client), 400 — the decision itself. */
    public static class DecisionRefused extends RuntimeException {
        private final int status;

        public DecisionRefused(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }

        public boolean gone() {
            return status == 404;
        }
    }

    public void decideDocument(UUID userId, UUID documentId, Decision decision) {
        decide("/internal/profiles/{userId}/documents/{id}/decision", userId, documentId, decision);
    }

    public void decideOrganization(UUID userId, UUID organizationId, Decision decision) {
        decide("/internal/profiles/{userId}/organizations/{id}/decision", userId, organizationId, decision);
    }

    private void decide(String uri, UUID userId, UUID id, Decision decision) {
        try {
            restClient.post().uri(uri, userId, id).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(decision.body()).retrieve().toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            throw new DecisionRefused(e.getStatusCode().value(), e.getResponseBodyAsString());
        }
    }

    /** The payer to check, with the client's DaData suggestion ({@code dadata}); empty when the client deleted it. */
    public Optional<com.fasterxml.jackson.databind.JsonNode> findOrganization(UUID userId, UUID organizationId) {
        try {
            String body = restClient.get().uri("/internal/profiles/{userId}/organizations/{id}", userId, organizationId)
                    .retrieve().body(String.class);
            return Optional.of(jsonMapper.readTree(body));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
