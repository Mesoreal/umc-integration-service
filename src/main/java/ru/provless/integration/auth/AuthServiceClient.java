package ru.provless.integration.auth;

import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** auth-service's internal API: it keeps the phones (personal managers name a client by phone). */
@Slf4j
@Component
public class AuthServiceClient {

    @Value("${auth-service.base-url}")
    private String baseUrl;

    @Value("${internal.api-key}")
    private String internalApiKey;

    @Value("${auth-service.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${auth-service.read-timeout-ms:5000}")
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
        log.info("AuthServiceClient initialized: baseUrl={}", baseUrl);
    }

    /** The account with this phone ({@code +7XXXXXXXXXX}); empty when there is none. Other failures throw. */
    public Optional<UUID> findUserByPhone(String phone) {
        try {
            String body = restClient.get()
                    .uri(b -> b.path("/internal/users/by-phone").queryParam("phone", "{phone}").build(phone))
                    .retrieve()
                    .body(String.class);
            return Optional.of(UUID.fromString(jsonMapper.readTree(body).path("user_id").asText()));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable answer of auth-service by-phone", e);
        }
    }
}
