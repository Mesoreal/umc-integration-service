package ru.provless.umc.client;

import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.provless.umc.exception.ProfileContactFetchException;
import ru.provless.umc.exception.ProfileFileFetchException;

import java.time.Duration;
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
                .defaultHeader("User-Agent", "umc-integration-service")
                .build();

        log.info("ProfileServiceClient initialized: baseUrl={} connectTimeoutMs={} readTimeoutMs={}",
                baseUrl, connectTimeoutMs, readTimeoutMs);
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
}
