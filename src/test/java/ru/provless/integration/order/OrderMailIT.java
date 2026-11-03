package ru.provless.integration.order;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import ru.provless.integration.chat.ChatConnectorTestBase;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The sales department's letter through the site (orders/task-site-order-email.md); site and catalog-service — WireMock. */
@TestPropertySource(properties = {"site.token=site-token", "site.order-letters=true", "chatwoot.public-url=https://chat.test"})
class OrderMailIT extends ChatConnectorTestBase {

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("catalog-service.base-url", chatwoot::baseUrl);
        registry.add("site.url", chatwoot::baseUrl);
    }

    @Autowired
    OrderMail mail;

    @Test
    void newOrder_letterWithEverything_personalManagerInSales() {
        UUID user = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        jdbc.update("TRUNCATE chatwoot.personal_manager");
        jdbc.update("INSERT INTO chatwoot.personal_manager (directus_id, user_id, agent_id, agent_name, notified) VALUES (?, ?, 7, 'Пётр', true)",
                UUID.randomUUID(), user);
        jdbc.update("INSERT INTO chatwoot.app_contact_link (user_id, contact_id) VALUES (?, 42)", user);
        chatwoot.stubFor(get(ACCOUNT + "/inbox_members/6").willReturn(okJson(
                "{\"payload\":[{\"id\":7,\"name\":\"Пётр Смирнов\",\"email\":\"petr@mesoreal.ru\"}]}")));
        chatwoot.stubFor(get("/internal/orders/" + order).willReturn(okJson("""
                {"user_id":"%s","buyer":{"full_name":"Иванова Анна","phone":"+79990000001"},
                 "order":{"number":1001,"created_at":"2026-11-03T10:15:00Z","items_count":2,
                          "total":{"amount":"4700.00","currency":"RUB"},"delivery_address":"г. Москва","organization":null,"comment":null,
                          "items":[{"name":"Крем","article":"ART-2","unit":"шт","quantity":2,"price":{"amount":"2350.00"},
                                    "line_total":{"amount":"4700.00"}}]}}""".formatted(user))));
        chatwoot.stubFor(post("/api/app/order-notifications/").willReturn(aResponse().withStatus(202)));

        mail.onOrderEvent(event("order.created", order));

        chatwoot.verify(postRequestedFor(urlEqualTo("/api/app/order-notifications/"))
                .withHeader("Authorization", equalTo("Bearer site-token"))
                .withRequestBody(equalToJson("""
                        {"order_id":"%s","number":1001,"placed_at":"2026-11-03T10:15:00Z",
                         "buyer":{"full_name":"Иванова Анна","phone":"+79990000001"},"organization":null,
                         "delivery_address":"г. Москва","comment":null,
                         "items":[{"name":"Крем","article":"ART-2","unit":"шт","quantity":2,"price":"2350.00","line_total":"4700.00"}],
                         "items_count":2,"total":"4700.00","currency":"RUB",
                         "manager":{"name":"Пётр Смирнов","email":"petr@mesoreal.ru"},
                         "chatwoot_url":"https://chat.test/app/accounts/1/contacts/42"}""".formatted(order))));

        // status changes are not letters; a site failure is retried by the broker (the listener throws)
        mail.onOrderEvent(event("order.status_changed", order));
        chatwoot.verify(1, postRequestedFor(urlEqualTo("/api/app/order-notifications/")));
        chatwoot.stubFor(post("/api/app/order-notifications/").willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> mail.onOrderEvent(event("order.created", order))).isInstanceOf(RuntimeException.class);
    }

    private static Message event(String type, UUID order) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(("{\"type\":\"" + type + "\",\"order_id\":\"" + order + "\",\"number\":1001}").getBytes(StandardCharsets.UTF_8), props);
    }
}
