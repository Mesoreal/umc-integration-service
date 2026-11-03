package ru.provless.integration.order;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.provless.integration.chat.ChatConversation;
import ru.provless.integration.chat.ChatConversations;
import ru.provless.integration.chat.ChatDepartments;
import ru.provless.integration.chat.ChatInboxes;
import ru.provless.integration.chat.ChatTransfers;
import ru.provless.integration.chat.OutboundEvent;
import ru.provless.integration.chatwoot.ChatwootClient;
import ru.provless.integration.chatwoot.ChatwootInboxMember;
import ru.provless.integration.manager.PersonalManager;
import ru.provless.integration.manager.PersonalManagerRepository;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A placed order reached the managers' side as the user's message (orders/plan-manager.md §2.1): the request goes to sales, to the
 * client's personal manager when they work there, and gets a note with the whole order. Once per order. Runs on the connector's
 * thread, in order with the user's messages.
 */
@Slf4j
@Service
public class OrderRouting {

    static final String SALES = "sales";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Europe/Moscow"));

    private final ChatConversations conversations;
    private final ChatTransfers transfers;
    private final ChatInboxes inboxes;
    private final ChatDepartments departments;
    private final ChatwootClient chatwoot;
    private final PersonalManagerRepository personalManagers;
    private final CatalogOrdersClient catalog;
    private final ChatOrderRepository routed;

    public OrderRouting(ChatConversations conversations, ChatTransfers transfers, ChatInboxes inboxes, ChatDepartments departments,
                        ChatwootClient chatwoot, PersonalManagerRepository personalManagers, CatalogOrdersClient catalog,
                        ChatOrderRepository routed) {
        this.conversations = conversations;
        this.transfers = transfers;
        this.inboxes = inboxes;
        this.departments = departments;
        this.chatwoot = chatwoot;
        this.personalManagers = personalManagers;
        this.catalog = catalog;
        this.routed = routed;
    }

    public void placed(UUID userId, OutboundEvent.Order order) {
        if (order.id() == null || routed.existsById(order.id())) {
            return;
        }
        ChatDepartments.Department sales = departments.byKey(SALES)
                .orElseThrow(() -> new IllegalStateException("No department «" + SALES + "» in chatwoot.chat.departments"));
        long salesInbox = inboxes.departmentInboxId(sales);
        ChatConversation request = conversations.existing(userId)
                .orElseThrow(() -> new IllegalStateException("No request of userId=" + userId + " for order " + order.number()));
        if (request.getInboxId() != salesInbox) {
            transfers.transfer(request, sales);
            request = conversations.existing(userId).orElseThrow();
        }
        long conversationId = request.getConversationId();
        assignPersonalManager(userId, request, salesInbox);
        chatwoot.createPrivateNote(conversationId, note(order), null);
        routed.save(new ChatOrder(order.id(), userId, order.number(), conversationId));
        log.info("Order {} of userId={} is in conversation {} of sales", order.number(), userId, conversationId);
    }

    /** The personal manager takes it when they are in the sales inbox; otherwise it stays as it is (the queue, or who has it). */
    private void assignPersonalManager(UUID userId, ChatConversation request, long salesInbox) {
        Long agent = personalManagers.findByUserId(userId).map(PersonalManager::getAgentId).orElse(null);
        if (agent == null || Objects.equals(agent, request.getAssigneeId())) {
            return;
        }
        boolean inSales = chatwoot.inboxMembers(salesInbox).stream().map(ChatwootInboxMember::id).anyMatch(agent::equals);
        if (!inSales) {
            log.info("Personal manager {} of userId={} is not in sales: the order stays in the queue", agent, userId);
            return;
        }
        chatwoot.assign(request.getConversationId(), agent);
    }

    /** The whole order from catalog-service; it does not answer — what the card has, the rest is in the order panel. */
    String note(OutboundEvent.Order order) {
        StringBuilder note = new StringBuilder("🛒 Заказ № ").append(order.number()).append(" · ")
                .append(STAMP.format(Instant.now())).append('\n');
        try {
            JsonNode whole = catalog.order(order.id());
            JsonNode buyer = whole.path("buyer");
            JsonNode o = whole.path("order");
            line(note, "Покупатель", join(text(buyer, "full_name"), text(buyer, "phone")));
            JsonNode org = o.path("organization");
            if (org.isObject()) {
                line(note, "Организация", join(text(org, "name"), text(org, "inn") == null ? null : "ИНН " + text(org, "inn")));
            }
            line(note, "Адрес", text(o, "delivery_address"));
            line(note, "Комментарий", text(o, "comment"));
            note.append('\n');
            int i = 1;
            for (JsonNode item : o.path("items")) {
                note.append(i++).append(". ").append(text(item, "name"));
                if (text(item, "article") != null) {
                    note.append(" (").append(text(item, "article")).append(')');
                }
                note.append(" — ").append(item.path("quantity").asInt()).append(' ')
                        .append(text(item, "unit") == null ? "шт" : text(item, "unit")).append(" × ")
                        .append(money(item.path("price").path("amount").asText())).append(" = ")
                        .append(money(item.path("line_total").path("amount").asText())).append(" ₽\n");
            }
            note.append("Итого: ").append(money(o.path("total").path("amount").asText())).append(" ₽ (")
                    .append(o.path("items_count").asInt()).append(" шт.)\n");
        } catch (RuntimeException e) {
            log.warn("Order {} not read from catalog-service, the note is short: {}", order.number(), e.getMessage());
            note.append(order.positionsCount()).append(" поз. на ").append(money(order.total().amount())).append(" ₽\n");
        }
        return note.append("Статус и доставка — в панели «Заказы клиента».").toString();
    }

    public static String money(String amount) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("ru"));
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0.00", symbols).format(new BigDecimal(amount));
    }

    private static void line(StringBuilder note, String label, String value) {
        if (value != null && !value.isBlank()) {
            note.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String join(String a, String b) {
        return a == null ? b : b == null ? a : a + ", " + b;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() || value.asText().isBlank() ? null : value.asText();
    }
}
