package ru.provless.integration.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.UUID;

/**
 * catalog-service's internal API of orders (orders/plan.md §4): the whole order with its buyer
 * ({@code GET /internal/orders/{id}} → {@code {user_id, buyer, order}}).
 */
@Slf4j
@Component
public class CatalogOrdersClient {

    @Value("${catalog-service.base-url}")
    private String baseUrl;

    @Value("${internal.api-key}")
    private String internalApiKey;

    private RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Internal-Auth", internalApiKey)
                .defaultHeader("User-Agent", "integration-service")
                .build();
        log.info("CatalogOrdersClient initialized: baseUrl={}", baseUrl);
    }

    public JsonNode order(UUID orderId) {
        String body = restClient.get().uri("/internal/orders/{id}", orderId).retrieve().body(String.class);
        try {
            return jsonMapper.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable order " + orderId, e);
        }
    }
}
