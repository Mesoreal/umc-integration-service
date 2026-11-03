package ru.provless.integration.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.provless.integration.chat.ChatDepartments;
import ru.provless.integration.chat.ChatInboxes;
import ru.provless.integration.chatwoot.ChatwootClient;
import ru.provless.integration.manager.PersonalManager;
import ru.provless.integration.manager.PersonalManagerRepository;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The sales department's letter about a new order (orders/task-site-order-email.md): the site sends it, we give it the order —
 * {@code POST {site}/api/app/order-notifications/}. Same token and site address as the account deletion requests (AUTH_SITE_*).
 * The site sends one letter per order, so a repeat after a lost answer is safe; a failure is retried, then goes to the DLQ.
 */
@Slf4j
@Component
public class OrderMail {

    static final String QUEUE = "integration.order-mail";

    private final CatalogOrdersClient catalog;
    private final PersonalManagerRepository personalManagers;
    private final ChatwootClient chatwoot;
    private final ChatInboxes inboxes;
    private final ChatDepartments departments;
    private final ChatwootLinks links;
    private final String url;
    private final String token;
    private final boolean chatwootEnabled;
    private final boolean lettersOn;
    private final ObjectMapper json = new ObjectMapper();
    private RestClient site;

    public OrderMail(CatalogOrdersClient catalog, PersonalManagerRepository personalManagers, ChatwootClient chatwoot,
                     ChatInboxes inboxes, ChatDepartments departments, ChatwootLinks links,
                     @Value("${site.url:}") String url, @Value("${site.token:}") String token,
                     @Value("${chatwoot.enabled:false}") boolean chatwootEnabled,
                     @Value("${site.order-letters:false}") boolean lettersOn) {
        this.catalog = catalog;
        this.personalManagers = personalManagers;
        this.chatwoot = chatwoot;
        this.inboxes = inboxes;
        this.departments = departments;
        this.links = links;
        this.url = url.replaceAll("/+$", "");
        this.token = token;
        this.chatwootEnabled = chatwootEnabled;
        this.lettersOn = lettersOn;
    }

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(15));
        site = RestClient.builder().baseUrl(url.isEmpty() ? "http://localhost" : url).requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader(HttpHeaders.USER_AGENT, "integration-service").build();
        log.info("Order letters via the site: enabled={} url={}", enabled(), url);
    }

    /** The site's address and token, and the switch: the site's method may not exist yet. */
    boolean enabled() {
        return lettersOn && !url.isEmpty() && !token.isEmpty();
    }

    @RabbitListener(queues = QUEUE)
    public void onOrderEvent(Message message) {
        JsonNode event;
        try {
            event = json.readTree(message.getBody());
        } catch (IOException e) {
            throw new AmqpRejectAndDontRequeueException("Unreadable order event", e);
        }
        if (!"order.created".equals(event.path("type").asText()) || !enabled()) {
            return;
        }
        UUID orderId = UUID.fromString(event.path("order_id").asText());
        Map<String, Object> letter = letter(orderId, catalog.order(orderId));
        try {
            site.post().uri("/api/app/order-notifications/").contentType(MediaType.APPLICATION_JSON).body(letter).retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // the site's error page is not worth the log: the code is
            throw new IllegalStateException("The site answered " + e.getStatusCode().value() + " to the letter of order "
                    + letter.get("number"));
        }
        log.info("Order {}: letter to the sales department handed to the site", letter.get("number"));
    }

    Map<String, Object> letter(UUID orderId, JsonNode whole) {
        JsonNode o = whole.path("order");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_id", orderId.toString());
        body.put("number", o.path("number").asLong());
        body.put("placed_at", text(o, "created_at"));
        body.put("buyer", Map.of("full_name", nz(text(whole.path("buyer"), "full_name")), "phone", nz(text(whole.path("buyer"), "phone"))));
        JsonNode org = o.path("organization");
        body.put("organization", org.isObject() ? Map.of("name", nz(text(org, "name")), "inn", nz(text(org, "inn"))) : null);
        body.put("delivery_address", text(o, "delivery_address"));
        body.put("comment", text(o, "comment"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode item : o.path("items")) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("name", text(item, "name"));
            line.put("article", text(item, "article"));
            line.put("unit", text(item, "unit"));
            line.put("quantity", item.path("quantity").asInt());
            line.put("price", text(item.path("price"), "amount"));
            line.put("line_total", text(item.path("line_total"), "amount"));
            items.add(line);
        }
        body.put("items", items);
        body.put("items_count", o.path("items_count").asInt());
        body.put("total", text(o.path("total"), "amount"));
        body.put("currency", text(o.path("total"), "currency"));
        body.put("manager", manager(text(whole, "user_id")));
        body.put("chatwoot_url", links.contact(text(whole, "user_id")));
        return body;
    }

    /** The personal manager when they work in sales (as the order's routing decides); otherwise null — the queue. */
    private Map<String, Object> manager(String userId) {
        if (userId == null || !chatwootEnabled) {
            return null;
        }
        Long agent = personalManagers.findByUserId(UUID.fromString(userId)).map(PersonalManager::getAgentId).orElse(null);
        if (agent == null) {
            return null;
        }
        long sales = inboxes.departmentInboxId(departments.byKey(OrderRouting.SALES).orElseThrow());
        return chatwoot.inboxMembers(sales).stream().filter(m -> m.id() == agent).findFirst()
                .<Map<String, Object>>map(m -> {
                    Map<String, Object> manager = new LinkedHashMap<>();
                    manager.put("name", m.name());
                    manager.put("email", m.email());
                    return manager;
                }).orElse(null);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() || value.asText().isBlank() ? null : value.asText();
    }

    @Configuration
    static class Queues {
        @Bean
        Queue integrationOrderMail() {
            return QueueBuilder.durable(QUEUE).deadLetterExchange("").deadLetterRoutingKey(QUEUE + ".dlq").build();
        }

        @Bean
        Queue integrationOrderMailDlq() {
            return QueueBuilder.durable(QUEUE + ".dlq").build();
        }

        @Bean
        Binding integrationOrderMailBinding(FanoutExchange ordersExchange) {
            return BindingBuilder.bind(integrationOrderMail()).to(ordersExchange);
        }
    }
}
