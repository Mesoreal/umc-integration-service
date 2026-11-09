package ru.provless.integration.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** The client's orders with {@code next_statuses}, newest first. */
    public JsonNode ofClient(UUID userId) {
        return send(() -> restClient.get().uri("/internal/orders?user_id={id}", userId).retrieve().body(String.class));
    }

    /** {@code comment} — optional words for the client, the app shows them under the status. */
    public JsonNode changeStatus(UUID orderId, String status, String changedBy, String comment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("changed_by", changedBy);
        body.put("comment", comment);
        return send(() -> restClient.post().uri("/internal/orders/{id}/status", orderId).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(String.class));
    }

    public JsonNode setDelivery(UUID orderId, String price, String date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("delivery_price", price);
        body.put("delivery_date", date);
        return send(() -> restClient.put().uri("/internal/orders/{id}/delivery", orderId).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(String.class));
    }

    /** catalog-service's refusal (4xx) with its answer, to pass on as it is. */
    public static class Refused extends RuntimeException {
        private final int status;
        private final String body;

        Refused(int status, String body) {
            super("catalog-service: " + status);
            this.status = status;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }
    }

    private JsonNode send(java.util.function.Supplier<String> call) {
        try {
            return jsonMapper.readTree(call.get());
        } catch (HttpClientErrorException e) {
            throw new Refused(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable answer of catalog-service", e);
        }
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
