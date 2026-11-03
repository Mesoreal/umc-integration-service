package ru.provless.integration.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.provless.integration.chatwoot.AppContactLinkRepository;
import ru.provless.integration.chatwoot.AppContactLink;
import ru.provless.integration.manager.DirectusClient;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Directus «Заказы» (orders/plan-manager.md §3): every event of an order writes its whole copy again — read from catalog-service,
 * so a lost or late event is repaired by the next one. A failure is retried, then goes to the DLQ (the next event of the order
 * repairs it too). No Directus configured — nothing is copied.
 */
@Slf4j
@Component
public class OrderCopy {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Europe/Moscow"));

    private final CatalogOrdersClient catalog;
    private final DirectusClient directus;
    private final AppContactLinkRepository contacts;
    private final String chatwootUrl;
    private final String accountId;
    private final ObjectMapper json = new ObjectMapper();

    public OrderCopy(CatalogOrdersClient catalog, DirectusClient directus, AppContactLinkRepository contacts,
                     @Value("${chatwoot.public-url:}") String chatwootUrl, @Value("${chatwoot.account-id:}") String accountId) {
        this.catalog = catalog;
        this.directus = directus;
        this.contacts = contacts;
        this.chatwootUrl = chatwootUrl.replaceAll("/+$", "");
        this.accountId = accountId;
    }

    @RabbitListener(queues = OrderQueues.QUEUE)
    public void onOrderEvent(Message message) {
        JsonNode event;
        UUID orderId;
        try {
            event = json.readTree(message.getBody());
            orderId = UUID.fromString(event.path("order_id").asText());
        } catch (IOException | IllegalArgumentException e) {
            throw new AmqpRejectAndDontRequeueException("Unreadable order event", e);
        }
        if (!directus.enabled()) {
            return;
        }
        JsonNode whole = catalog.order(orderId);
        directus.upsertOrder(orderId, fields(whole, event));
        log.debug("Order {} copied to Directus ({})", whole.path("order").path("number").asLong(), event.path("type").asText());
    }

    Map<String, Object> fields(JsonNode whole, JsonNode event) {
        JsonNode o = whole.path("order");
        JsonNode buyer = whole.path("buyer");
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("number", o.path("number").asLong());
        f.put("placed_at", text(o, "created_at"));
        f.put("status", OrderLabels.status(text(o, "status")));
        f.put("buyer_name", text(buyer, "full_name"));
        f.put("buyer_phone", text(buyer, "phone"));
        JsonNode org = o.path("organization");
        f.put("organization", org.isObject() ? text(org, "name") + (text(org, "inn") == null ? "" : ", ИНН " + text(org, "inn")) : null);
        f.put("delivery_address", text(o, "delivery_address"));
        f.put("items", items(o));
        f.put("total", text(o.path("total"), "amount"));
        f.put("delivery_price", text(o.path("delivery_price"), "amount"));
        f.put("delivery_date", text(o, "delivery_date"));
        f.put("comment", text(o, "comment"));
        f.put("last_change", lastChange(event));
        f.put("chatwoot_url", contactUrl(whole));
        return f;
    }

    private static String items(JsonNode order) {
        StringBuilder lines = new StringBuilder();
        int i = 1;
        for (JsonNode item : order.path("items")) {
            lines.append(i++).append(". ").append(text(item, "name"));
            if (text(item, "article") != null) {
                lines.append(" (").append(text(item, "article")).append(')');
            }
            lines.append(" — ").append(item.path("quantity").asInt()).append(' ')
                    .append(text(item, "unit") == null ? "шт" : text(item, "unit")).append(" × ")
                    .append(OrderRouting.money(item.path("price").path("amount").asText("0"))).append(" = ")
                    .append(OrderRouting.money(item.path("line_total").path("amount").asText("0"))).append(" ₽\n");
        }
        return lines.toString().strip();
    }

    /** «Пётр: Собираем заказ, 03.11 15:22»; the client — «клиент». */
    private static String lastChange(JsonNode event) {
        String type = event.path("type").asText("");
        String at = STAMP.format(event.hasNonNull("at") ? Instant.parse(event.path("at").asText()) : Instant.now());
        return switch (type) {
            case "order.created" -> "оформлен клиентом, " + at;
            case "order.status_changed" -> {
                String by = event.path("changed_by").asText("");
                String status = OrderLabels.status(event.path("status").asText());
                yield ("client".equals(by) ? "клиент" : by) + ": " + status + ", " + at;
            }
            case "order.delivery_changed" -> "доставка изменена, " + at;
            default -> type + ", " + at;
        };
    }

    private String contactUrl(JsonNode whole) {
        String userId = text(whole, "user_id");
        if (chatwootUrl.isEmpty() || userId == null) {
            return null;
        }
        return contacts.findById(UUID.fromString(userId)).map(AppContactLink::getContactId)
                .map(contact -> chatwootUrl + "/app/accounts/" + accountId + "/contacts/" + contact).orElse(null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() || value.asText().isBlank() ? null : value.asText();
    }
}
