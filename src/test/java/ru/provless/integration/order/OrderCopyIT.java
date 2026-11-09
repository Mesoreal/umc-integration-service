package ru.provless.integration.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import ru.provless.integration.chat.ChatConnectorTestBase;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/** Directus «Заказы» (orders/plan-manager.md §3): every order event writes the whole copy. Directus and catalog-service — WireMock. */
@TestPropertySource(properties = {"directus.token=svc-token", "chatwoot.public-url=https://chat.test/"})
class OrderCopyIT extends ChatConnectorTestBase {

    @Autowired
    OrderCopy copy;

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("catalog-service.base-url", chatwoot::baseUrl);
        registry.add("directus.url", () -> chatwoot.baseUrl() + "/directus");
    }

    @Test
    void created_thenStatus_wholeCopyEachTime() throws Exception {
        UUID user = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO chatwoot.app_contact_link (user_id, contact_id) VALUES (?, 42)", user);
        chatwoot.stubFor(get("/internal/orders/" + order).willReturn(okJson("""
                {"user_id":"%s","buyer":{"full_name":"Иванова Анна","phone":"+79990000001"},
                 "order":{"number":1001,"status":"pending","created_at":"2026-11-03T10:15:00Z","items_count":3,
                          "total":{"amount":"6590.00","currency":"RUB"},"delivery_address":"г. Москва, ул. Тверская, д. 10",
                          "organization":{"name":"ООО Клиника","inn":"7701234567"},"comment":"Позвонить",
                          "items":[{"name":"Крем","article":"ART-2","unit":"шт","quantity":2,"price":{"amount":"2350.00"},
                                    "line_total":{"amount":"4700.00"}}]}}""".formatted(user))));
        chatwoot.stubFor(get(urlPathEqualTo("/directus/items/orders")).willReturn(okJson("{\"data\":[]}")));
        chatwoot.stubFor(post("/directus/items/orders").willReturn(okJson("{\"data\":{}}")));

        event("""
                {"type":"order.created","event_id":"%s","order_id":"%s","number":1001,"user_id":"%s","status":"pending"}"""
                .formatted(UUID.randomUUID(), order, user));

        await(() -> chatwoot.verify(postRequestedFor(urlEqualTo("/directus/items/orders"))
                .withHeader("Authorization", equalTo("Bearer svc-token"))
                .withRequestBody(equalToJson("""
                        {"id":"%s","number":1001,"placed_at":"2026-11-03T10:15:00Z","status":"Принят","buyer_name":"Иванова Анна",
                         "buyer_phone":"+79990000001","organization":"ООО Клиника, ИНН 7701234567",
                         "delivery_address":"г. Москва, ул. Тверская, д. 10","items":"1. Крем (ART-2) — 2 шт × 2 350,00 = 4 700,00 ₽",
                         "total":"6590.00","delivery_price":null,"delivery_date":null,"comment":"Позвонить",
                         "last_change":"${json-unit.any-string}","chatwoot_url":"https://chat.test/app/accounts/1/contacts/42"}"""
                        .formatted(order)))));

        // the copy is there now: the next event patches it
        chatwoot.stubFor(get(urlPathEqualTo("/directus/items/orders")).willReturn(okJson("{\"data\":[{\"id\":\"" + order + "\"}]}")));
        chatwoot.stubFor(patch(urlEqualTo("/directus/items/orders/" + order)).willReturn(okJson("{\"data\":{}}")));
        event("""
                {"type":"order.status_changed","event_id":"%s","order_id":"%s","number":1001,"user_id":"%s","status":"processing",
                 "changed_by":"Пётр","at":"2026-11-03T12:30:00Z"}""".formatted(UUID.randomUUID(), order, user));

        await(() -> chatwoot.verify(patchRequestedFor(urlEqualTo("/directus/items/orders/" + order))
                .withRequestBody(matchingJsonPath("$.last_change", equalTo("Пётр: Собираем заказ, 03.11 15:30")))));
    }

    /** Straight to the listener: the other test contexts of the run listen on the same queue and would take the event. */
    private void event(String json) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        copy.onOrderEvent(new Message(json.getBytes(StandardCharsets.UTF_8), props));
    }

    private static void await(Runnable check) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            try {
                check.run();
                return;
            } catch (AssertionError | RuntimeException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
                Thread.sleep(200);
            }
        }
    }
}
