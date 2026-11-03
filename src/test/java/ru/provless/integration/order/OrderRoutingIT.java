package ru.provless.integration.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.provless.integration.chat.ChatConnectorTestBase;
import ru.provless.integration.chat.ChatQueues;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A placed order in the chat (orders/plan-manager.md §2.1): the user's order message goes to the request, the request to sales
 * («Приложение — продажи» = inbox 6 in the tests), to the personal manager when they are in sales, with the order's note.
 * catalog-service is the Chatwoot WireMock too.
 */
class OrderRoutingIT extends ChatConnectorTestBase {

    @DynamicPropertySource
    static void catalog(DynamicPropertyRegistry registry) {
        registry.add("catalog-service.base-url", chatwoot::baseUrl);
    }

    private UUID user;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        user = UUID.randomUUID();
        orderId = UUID.randomUUID();
        jdbc.update("TRUNCATE chatwoot.chat_order, chatwoot.personal_manager");
        drain(ChatQueues.INBOUND);
        chatwoot.stubFor(post(urlPathEqualTo(ACCOUNT + "/conversations/30/messages")).willReturn(okJson("{\"id\":500,\"created_at\":1793490000}")));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/30/assignments").willReturn(okJson("{}")));
        chatwoot.stubFor(get(ACCOUNT + "/inbox_members/6").willReturn(okJson("{\"payload\":[{\"id\":7,\"name\":\"Пётр\"}]}")));
    }

    @Test
    void orderInIntake_movedToSales_personalManager_note_once() {
        jdbc.update("INSERT INTO chatwoot.chat_conversation (conversation_id, user_id, contact_id, inbox_id, status, current) "
                + "VALUES (15, ?, 42, 5, 'open', true)", user);
        jdbc.update("INSERT INTO chatwoot.personal_manager (directus_id, user_id, agent_id, agent_name, notified) VALUES (?, ?, 7, 'Пётр', true)",
                UUID.randomUUID(), user);
        chatwoot.stubFor(get(ACCOUNT + "/conversations/15").willReturn(okJson(
                "{\"id\":15,\"inbox_id\":5,\"status\":\"open\",\"meta\":{},\"contact_inbox\":{\"source_id\":\"" + user + "\"}}")));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages").willReturn(okJson("{\"id\":301,\"created_at\":1793480000}")));
        // the transfer: a new conversation in sales, the order message copied, the old one deleted
        chatwoot.stubFor(post(ACCOUNT + "/contacts/42/contact_inboxes").willReturn(okJson("{}")));
        chatwoot.stubFor(post(ACCOUNT + "/conversations").withRequestBody(matchingJsonPath("$.inbox_id", equalTo("6")))
                .willReturn(okJson("{\"id\":30,\"inbox_id\":6,\"status\":\"open\",\"meta\":{}}")));
        chatwoot.stubFor(get(urlPathEqualTo(ACCOUNT + "/conversations/15/messages")).willReturn(okJson("""
                {"payload":[{"id":301,"message_type":0,"content":"🛒 Заказ № 1001","created_at":1793480000,"private":false,
                             "sender":{"name":"Клиент","type":"contact"}}]}""")));
        chatwoot.stubFor(delete(ACCOUNT + "/conversations/15").willReturn(aResponse().withStatus(200)));
        chatwoot.stubFor(get(ACCOUNT + "/conversations/30").willReturn(okJson("{\"id\":30,\"inbox_id\":6,\"status\":\"open\",\"meta\":{}}")));
        chatwoot.stubFor(get("/internal/orders/" + orderId).willReturn(okJson("""
                {"user_id":"%s","buyer":{"full_name":"Иванова Анна","phone":"+79990000001"},
                 "order":{"number":1001,"items_count":3,"total":{"amount":"6590.00","currency":"RUB"},
                          "delivery_address":"г. Москва, ул. Тверская, д. 10","organization":{"name":"ООО Клиника","inn":"7701234567"},
                          "comment":"Позвонить","items":[
                   {"name":"Сыворотка","article":"ART-1","unit":"шт","quantity":1,"price":{"amount":"1890.00"},"line_total":{"amount":"1890.00"}},
                   {"name":"Крем","article":"ART-2","unit":"шт","quantity":2,"price":{"amount":"2350.00"},"line_total":{"amount":"4700.00"}}]}}
                """.formatted(user))));

        UUID messageId = UUID.randomUUID();
        outbound(orderMessage(messageId));
        receiveUntil(ChatQueues.INBOUND, b -> b.contains("client.message_delivered"), 10_000);
        awaitRouted();

        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/15/messages"))
                .withRequestBody(matchingJsonPath("$.content", equalTo("🛒 Заказ № 1001 · 2 поз. · 6 590,00 ₽"))));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/30/assignments"))
                .withRequestBody(equalToJson("{\"assignee_id\":7}")));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/30/messages"))
                .withRequestBody(matchingJsonPath("$.private", equalTo("true")))
                .withRequestBody(containing("Покупатель: Иванова Анна, +79990000001"))
                .withRequestBody(containing("Организация: ООО Клиника, ИНН 7701234567"))
                .withRequestBody(containing("2. Крем (ART-2) — 2 шт × 2 350,00 = 4 700,00 ₽"))
                .withRequestBody(containing("Итого: 6 590,00 ₽ (3 шт.)")));
        assertThat(jdbc.queryForObject("SELECT conversation_id FROM chatwoot.chat_order WHERE order_id = ?", Long.class, orderId))
                .isEqualTo(30L);

        // the same event again: nothing more
        outbound(orderMessage(messageId));
        receiveUntil(ChatQueues.INBOUND, b -> b.contains("client.message_delivered"), 10_000);
        chatwoot.verify(1, postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/30/messages")).withRequestBody(containing("Покупатель")));
    }

    @Test
    void alreadyInSales_noPersonalManager_catalogDown_shortNote() {
        jdbc.update("INSERT INTO chatwoot.chat_conversation (conversation_id, user_id, contact_id, inbox_id, status, current) "
                + "VALUES (30, ?, 42, 6, 'open', true)", user);
        chatwoot.stubFor(get(ACCOUNT + "/conversations/30").willReturn(okJson(
                "{\"id\":30,\"inbox_id\":6,\"status\":\"open\",\"meta\":{},\"contact_inbox\":{\"source_id\":\"" + user + "\"}}")));
        chatwoot.stubFor(get("/internal/orders/" + orderId).willReturn(aResponse().withStatus(503)));

        outbound(orderMessage(UUID.randomUUID()));
        receiveUntil(ChatQueues.INBOUND, b -> b.contains("client.message_delivered"), 10_000);
        awaitRouted();

        chatwoot.verify(0, postRequestedFor(urlEqualTo(ACCOUNT + "/conversations")));
        chatwoot.verify(0, postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/30/assignments")));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/30/messages"))
                .withRequestBody(matchingJsonPath("$.private", equalTo("true")))
                .withRequestBody(containing("2 поз. на 6 590,00 ₽")));
    }

    private String orderMessage(UUID messageId) {
        return """
                {"type":"message.created","event_id":"%s","user_id":"%s","message_id":"%s","seq":1,"text":"Заказ № 1001 оформлен",
                 "attachments":[],"created_at":"2026-11-03T10:00:00Z",
                 "order":{"id":"%s","number":1001,"status":"pending","positions_count":2,"total":{"amount":"6590.00","currency":"RUB"}}}
                """.formatted(UUID.randomUUID(), user, messageId, orderId);
    }

    private void awaitRouted() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (jdbc.queryForObject("SELECT count(*) FROM chatwoot.chat_order WHERE order_id = ?", Integer.class, orderId) > 0) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("order " + orderId + " never routed");
    }
}
