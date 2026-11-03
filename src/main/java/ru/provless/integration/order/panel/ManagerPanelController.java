package ru.provless.integration.order.panel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.provless.integration.chatwoot.ChatwootClient;
import ru.provless.integration.order.CatalogOrdersClient;
import ru.provless.integration.order.OrderLabels;
import ru.provless.integration.order.OrderRouting;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * The managers' panel «Заказы клиента» (orders/plan-manager.md §2.2): the page and its API. The API passes the managers' changes to
 * catalog-service and notes them in the conversation; who changed — the agent's name Chatwoot gave the page.
 */
@Slf4j
@RestController
public class ManagerPanelController {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StatusRequest(String status, String agentName, Long conversationId) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeliveryRequest(String deliveryPrice, String deliveryDate, String agentName, Long conversationId) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final CatalogOrdersClient catalog;
    private final ChatwootClient chatwoot;

    public ManagerPanelController(CatalogOrdersClient catalog, ChatwootClient chatwoot) {
        this.catalog = catalog;
        this.chatwoot = chatwoot;
    }

    private static final ClassPathResource PAGE = new ClassPathResource("manager-panel/orders.html");

    /** The page itself; its key stays in the address. Not cached: a new version shows on the next opening. */
    @GetMapping("/manager-panel/orders")
    public ResponseEntity<Resource> page() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore()).body(PAGE);
    }

    @GetMapping("/manager-panel/api/orders")
    public JsonNode orders(@RequestParam("user_id") UUID userId) {
        return catalog.ofClient(userId);
    }

    @PostMapping("/manager-panel/api/orders/{id}/status")
    public JsonNode status(@PathVariable("id") UUID id, @RequestBody StatusRequest body) {
        String agent = agent(body.agentName());
        JsonNode order = catalog.changeStatus(id, body.status(), agent);
        note(body.conversationId(), agent + ": заказ № " + order.path("number").asLong() + " → "
                + OrderLabels.status(order.path("status").asText()));
        return order;
    }

    @PutMapping("/manager-panel/api/orders/{id}/delivery")
    public JsonNode delivery(@PathVariable("id") UUID id, @RequestBody DeliveryRequest body) {
        String agent = agent(body.agentName());
        JsonNode order = catalog.setDelivery(id, blankToNull(body.deliveryPrice()), blankToNull(body.deliveryDate()));
        String price = order.path("delivery_price").path("amount").asText(null);
        String date = order.path("delivery_date").asText(null);
        note(body.conversationId(), agent + ": заказ № " + order.path("number").asLong() + " — доставка "
                + (price == null ? "без стоимости" : OrderRouting.money(price) + " ₽")
                + (date == null ? "" : ", " + LocalDate.parse(date).format(DAY)));
        return order;
    }

    @ExceptionHandler(CatalogOrdersClient.Refused.class)
    public ResponseEntity<String> refused(CatalogOrdersClient.Refused e) {
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON).body(e.body());
    }

    /** For the history of the conversation; the change is made already, a failed note only logged. */
    private void note(Long conversationId, String text) {
        if (conversationId == null) {
            return;
        }
        try {
            chatwoot.createPrivateNote(conversationId, text, null);
        } catch (RuntimeException e) {
            log.warn("Panel note not posted to conversation {}: {}", conversationId, e.getMessage());
        }
    }

    private static String agent(String name) {
        return name == null || name.isBlank() ? "Менеджер" : name.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
