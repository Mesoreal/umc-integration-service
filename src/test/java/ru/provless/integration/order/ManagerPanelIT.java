package ru.provless.integration.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.provless.integration.chat.ChatConnectorTestBase;
import ru.provless.integration.order.panel.ManagerPanelSetup;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The managers' panel «Заказы клиента» (orders/plan-manager.md §2.2); catalog-service is the Chatwoot WireMock too. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "manager-panel.key=panel-secret",
        "manager-panel.url=https://app-test.mesoreal.ru/manager-panel/orders"
})
class ManagerPanelIT extends ChatConnectorTestBase {

    @DynamicPropertySource
    static void catalog(DynamicPropertyRegistry registry) {
        registry.add("catalog-service.base-url", chatwoot::baseUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired ManagerPanelSetup setup;

    private final UUID user = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();

    @BeforeEach
    void stubs() {
        chatwoot.stubFor(post(ACCOUNT + "/conversations/30/messages").willReturn(okJson("{\"id\":700,\"created_at\":1793490000}")));
    }

    @Test
    void theKeyIsRequired_thePageIsNot() throws Exception {
        mvc.perform(request(org.springframework.http.HttpMethod.GET, "/manager-panel/api/orders?user_id=" + user))
                .andExpect(status().isUnauthorized());
        mvc.perform(request(org.springframework.http.HttpMethod.GET, "/manager-panel/api/orders?user_id=" + user)
                .header("X-Panel-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(request(org.springframework.http.HttpMethod.GET, "/manager-panel/orders?key=x"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("chatwoot-dashboard-app:fetch-info")));
    }

    @Test
    void list_status_delivery_throughCatalog_notedInTheConversation() throws Exception {
        chatwoot.stubFor(get(urlPathEqualTo("/internal/orders")).withQueryParam("user_id", equalTo(user.toString()))
                .willReturn(okJson("[{\"id\":\"" + order + "\",\"number\":1001,\"status\":\"pending\",\"next_statuses\":[\"invoiced\",\"cancelled\"]}]")));
        chatwoot.stubFor(post("/internal/orders/" + order + "/status")
                .willReturn(okJson("{\"id\":\"" + order + "\",\"number\":1001,\"status\":\"invoiced\"}")));
        chatwoot.stubFor(put("/internal/orders/" + order + "/delivery")
                .willReturn(okJson("{\"id\":\"" + order + "\",\"number\":1001,\"status\":\"processing\","
                        + "\"delivery_price\":{\"amount\":\"450.00\",\"currency\":\"RUB\"},\"delivery_date\":\"2026-11-05\"}")));

        api("GET", "/orders?user_id=" + user, null).andExpect(status().isOk()).andExpect(jsonPath("$[0].number").value(1001));
        api("POST", "/orders/" + order + "/status",
                "{\"status\":\"invoiced\",\"comment\":\" Оплата до пятницы \",\"agent_name\":\"Пётр\",\"conversation_id\":30}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("invoiced"));
        api("PUT", "/orders/" + order + "/delivery",
                "{\"delivery_price\":\"450\",\"delivery_date\":\"2026-11-05\",\"agent_name\":\"Пётр\",\"conversation_id\":30}")
                .andExpect(status().isOk());

        chatwoot.verify(postRequestedFor(urlEqualTo("/internal/orders/" + order + "/status"))
                .withRequestBody(equalToJson("{\"status\":\"invoiced\",\"changed_by\":\"Пётр\",\"comment\":\"Оплата до пятницы\"}")));
        chatwoot.verify(putRequestedFor(urlEqualTo("/internal/orders/" + order + "/delivery"))
                .withRequestBody(equalToJson("{\"delivery_price\":\"450\",\"delivery_date\":\"2026-11-05\"}")));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/30/messages"))
                .withRequestBody(matchingJsonPath("$.private", equalTo("true")))
                .withRequestBody(containing("Пётр: заказ № 1001 → Ждёт оплаты\\nКлиенту: Оплата до пятницы")));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/30/messages"))
                .withRequestBody(containing("Пётр: заказ № 1001 — доставка 450,00 ₽, 05.11.2026")));
    }

    @Test
    void catalogRefuses_theAnswerPassedOn_noNote() throws Exception {
        chatwoot.stubFor(post("/internal/orders/" + order + "/status").willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":\"ORDER_STATUS_TRANSITION\",\"message\":\"Order 1001 cannot go from delivered to pending\"}")));

        api("POST", "/orders/" + order + "/status", "{\"status\":\"pending\",\"agent_name\":\"Пётр\",\"conversation_id\":30}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error_code").value("ORDER_STATUS_TRANSITION"));
        chatwoot.verify(0, postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/30/messages")));
    }

    @Test
    void dashboardApp_registered_thenRepointed() {
        chatwoot.stubFor(get(ACCOUNT + "/dashboard_apps").willReturn(okJson("[]")));
        chatwoot.stubFor(post(ACCOUNT + "/dashboard_apps").willReturn(okJson("{\"id\":3}")));
        setup.ensure();
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/dashboard_apps")).withRequestBody(equalToJson("""
                {"dashboard_app":{"title":"Заказы клиента","content":[{"type":"frame",
                 "url":"https://app-test.mesoreal.ru/manager-panel/orders?key=panel-secret"}]}}""")));

        chatwoot.stubFor(get(ACCOUNT + "/dashboard_apps").willReturn(okJson(
                "[{\"id\":3,\"title\":\"Заказы клиента\",\"content\":[{\"type\":\"frame\",\"url\":\"https://old/x?key=old\"}]}]")));
        chatwoot.stubFor(patch(urlEqualTo(ACCOUNT + "/dashboard_apps/3")).willReturn(okJson("{\"id\":3}")));
        setup.ensure();
        chatwoot.verify(patchRequestedFor(urlEqualTo(ACCOUNT + "/dashboard_apps/3"))
                .withRequestBody(containing("manager-panel/orders?key=panel-secret")));
    }

    private org.springframework.test.web.servlet.ResultActions api(String method, String path, String body) throws Exception {
        var request = request(org.springframework.http.HttpMethod.valueOf(method), "/manager-panel/api" + path)
                .header("X-Panel-Key", "panel-secret");
        if (body != null) {
            request.contentType("application/json").content(body);
        }
        return mvc.perform(request);
    }
}
