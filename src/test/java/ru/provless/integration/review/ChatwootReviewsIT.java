package ru.provless.integration.review;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ru.provless.integration.chat.ChatConnectorTestBase;
import ru.provless.integration.chatwoot.AppContactService;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Chatwoot inbox «Проверка документов» (front requests S5 §10): a payer from profile-service → a conversation with the note and
 * the licence → the decision in the panel → profile-service. WireMock stands for Chatwoot; another for profile-service and Directus.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "manager-panel.key=panel-secret",
        "directus.token=test-directus-token",
        "review.inbox-name=Проверка документов",
        "review.retry-initial-delay=PT1H",
        "review.reasons-initial-delay=PT1H",
        "review.amo-reasons-initial-delay=PT1H"
})
class ChatwootReviewsIT extends ChatConnectorTestBase {

    static WireMockServer services = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        services.start();
    }

    @DynamicPropertySource
    static void serviceEndpoints(DynamicPropertyRegistry registry) {
        registry.add("directus.url", () -> services.baseUrl() + "/directus");
        registry.add("profile-service.base-url", services::baseUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired AppContactService contacts;
    @Autowired ChatwootReviews reviews;
    @Autowired ReviewRequestRepository requests;
    @Autowired ReviewRouting routing;

    private final UUID user = UUID.randomUUID();
    private final UUID payer = UUID.randomUUID();
    private final UUID licence = UUID.randomUUID();

    @BeforeEach
    void stubs() {
        services.resetAll();
        jdbc.update("DELETE FROM umc.review_request");
        when(contacts.ensure(any(), isNull())).thenReturn(77L);
        chatwoot.stubFor(post(ACCOUNT + "/inboxes").willReturn(okJson("{\"id\":9}")));
        chatwoot.stubFor(post(ACCOUNT + "/contacts/77/contact_inboxes").willReturn(okJson("{}")));
        chatwoot.stubFor(post(ACCOUNT + "/conversations").willReturn(okJson("{\"id\":41,\"inbox_id\":9,\"status\":\"open\"}")));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/41/messages").willReturn(okJson("{\"id\":800,\"created_at\":1793490000}")));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/41/toggle_status").willReturn(okJson("{}")));

        services.stubFor(get("/internal/profiles/" + user + "/contact-info")
                .willReturn(okJson("{\"phone\":\"+79001234567\",\"fullName\":\"Иванова Анна\"}")));
        services.stubFor(get("/internal/profiles/" + user + "/organizations/" + payer).willReturn(okJson("""
                {"id":"%s","payer_type":"LEGAL","name":"ООО Клиника","inn":"7701234567","kpp":"770101001","address":"Москва",
                 "license_number":"ЛО-77-01-1","license_valid_until":"2027-03-01","license_file_id":"%s",
                 "dadata":{"inn":"7701234567","kpp":"770101002","ogrn":"1027700000000","type":"LEGAL","state":{"status":"ACTIVE"},
                           "name":{"short_with_opf":"ООО «Клиника»"},"address":{"value":"г Москва, ул Тверская, д 1"},
                           "management":{"name":"Петров Пётр","post":"Главный врач"}}}""".formatted(payer, licence))));
        services.stubFor(get("/internal/storage/" + user + "/" + licence)
                .willReturn(aResponse().withHeader("Content-Type", "application/pdf").withBody("%PDF-1.4")));
        services.stubFor(get(urlPathEqualTo("/directus/items/rejection_reasons")).willReturn(okJson("""
                {"data":[{"code":"UNREADABLE","text":"Фото размыто","applies_to":["document","payer"],"active":true,"sort":1},
                         {"code":"LICENSE_INVALID","text":"Лицензия не найдена или не действует","applies_to":["payer"],"active":true,"sort":2},
                         {"code":"NOT_MEDICAL","text":"Не медицинское образование","applies_to":["document"],"active":true,"sort":3},
                         {"code":"OLD","text":"Старая причина","applies_to":["payer"],"active":false,"sort":4}]}""")));
        services.stubFor(post("/internal/profiles/" + user + "/organizations/" + payer + "/decision").willReturn(okJson("{}")));
    }

    @Test
    void payerAdded_requestInTheInbox_withDadataAndTheLicence_decidedInThePanel() throws Exception {
        // the listener itself (not through the broker: cached contexts of other tests consume the same queue)
        routing.onPayerAdded(new ReviewRouting.PayerAddedEvent(user, payer));
        assertThat(requests.findByKindAndSubjectId(RejectionReasons.Kind.PAYER, payer).orElseThrow().getStatus())
                .isEqualTo(ReviewRequest.Status.OPEN);

        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations"))
                .withRequestBody(matchingJsonPath("$.inbox_id", equalTo("9")))
                .withRequestBody(matchingJsonPath("$.contact_id", equalTo("77")))
                .withRequestBody(matchingJsonPath("$.additional_attributes.review_kind", equalTo("PAYER"))));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/41/messages"))
                .withRequestBody(matchingJsonPath("$.private", equalTo("true")))
                .withRequestBody(containing("Плательщик на проверку: организация"))
                .withRequestBody(containing("Клиент: Иванова Анна, +79001234567"))
                .withRequestBody(containing("Лицензия: ЛО-77-01-1, до 01.03.2027"))
                .withRequestBody(containing("Статус: действует"))
                .withRequestBody(containing("Руководитель: Петров Пётр, Главный врач"))
                .withRequestBody(containing("Отличается от введённого: КПП, адрес.")));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/41/messages"))
                .withHeader("Content-Type", containing("multipart/form-data")).withRequestBody(containing("%PDF-1.4")));

        // the panel: reasons of payers only, active ones, then «Другая причина»
        api("GET", "/reviews?conversation_id=41", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("PAYER"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.reasons.length()").value(3))
                .andExpect(jsonPath("$.reasons[1].code").value("LICENSE_INVALID"))
                .andExpect(jsonPath("$.reasons[2].code").value("OTHER"));
        api("GET", "/reviews?conversation_id=999", null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error_code").value("NO_REVIEW"));

        String id = requests.findByKindAndSubjectId(RejectionReasons.Kind.PAYER, payer).orElseThrow().getId().toString();
        api("POST", "/reviews/" + id + "/decision", "{\"approved\":false,\"reason\":\"NOT_MEDICAL\",\"agent_name\":\"Пётр\"}")
                .andExpect(status().isBadRequest()); // a document reason, not for a payer
        api("POST", "/reviews/" + id + "/decision", "{\"approved\":false,\"reason\":\"OTHER\",\"agent_name\":\"Пётр\"}")
                .andExpect(status().isBadRequest()); // OTHER needs the comment
        api("POST", "/reviews/" + id + "/decision",
                "{\"approved\":false,\"reason\":\"LICENSE_INVALID\",\"comment\":\"Срок истёк\",\"agent_name\":\"Пётр\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECIDED"))
                .andExpect(jsonPath("$.decision").value("REJECTED")).andExpect(jsonPath("$.decided_by").value("Пётр"));

        services.verify(postRequestedFor(urlEqualTo("/internal/profiles/" + user + "/organizations/" + payer + "/decision"))
                .withRequestBody(equalToJson("""
                        {"approved":false,"reason":"LICENSE_INVALID","reason_text":"Лицензия не найдена или не действует",
                         "comment":"Срок истёк","summary":null}""")));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/41/messages"))
                .withRequestBody(containing("Пётр: отклонено — Лицензия не найдена или не действует\\nКомментарий: Срок истёк")));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/41/toggle_status"))
                .withRequestBody(equalToJson("{\"status\":\"resolved\"}")));

        api("POST", "/reviews/" + id + "/decision", "{\"approved\":true}").andExpect(status().isConflict());
    }

    @Test
    void chatwootFailing_requestWaits_theRetryPassDeliversIt() {
        chatwoot.stubFor(post(ACCOUNT + "/conversations").willReturn(aResponse().withStatus(503)));
        reviews.open(RejectionReasons.Kind.PAYER, payer, user, null);
        ReviewRequest queued = requests.findByKindAndSubjectId(RejectionReasons.Kind.PAYER, payer).orElseThrow();
        assertThat(queued.getStatus()).isEqualTo(ReviewRequest.Status.QUEUED);
        assertThat(queued.getAttempts()).isEqualTo(1);

        chatwoot.stubFor(post(ACCOUNT + "/conversations").willReturn(okJson("{\"id\":41,\"inbox_id\":9,\"status\":\"open\"}")));
        reviews.deliverQueued();
        ReviewRequest open = requests.findById(queued.getId()).orElseThrow();
        assertThat(open.getStatus()).isEqualTo(ReviewRequest.Status.OPEN);
        assertThat(open.getConversationId()).isEqualTo(41L);
    }

    @Test
    void payerDeleted_beforeTheDecision_requestCancelled() throws Exception {
        reviews.open(RejectionReasons.Kind.PAYER, payer, user, null);
        services.stubFor(post("/internal/profiles/" + user + "/organizations/" + payer + "/decision")
                .willReturn(aResponse().withStatus(404)));
        String id = requests.findByKindAndSubjectId(RejectionReasons.Kind.PAYER, payer).orElseThrow().getId().toString();

        api("POST", "/reviews/" + id + "/decision", "{\"approved\":true,\"agent_name\":\"Пётр\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Заявки больше нет: клиент удалил плательщика"));
        assertThat(requests.findById(UUID.fromString(id)).orElseThrow().getStatus()).isEqualTo(ReviewRequest.Status.CANCELLED);
    }

    @Test
    void payerGone_beforeTheRequest_cancelledWithoutAConversation() {
        services.stubFor(get("/internal/profiles/" + user + "/organizations/" + payer).willReturn(aResponse().withStatus(404)));
        reviews.open(RejectionReasons.Kind.PAYER, payer, user, null);
        assertThat(requests.findByKindAndSubjectId(RejectionReasons.Kind.PAYER, payer).orElseThrow().getStatus())
                .isEqualTo(ReviewRequest.Status.CANCELLED);
        chatwoot.verify(0, postRequestedFor(urlEqualTo(ACCOUNT + "/conversations")));
    }

    private ResultActions api(String method, String path, String body) throws Exception {
        var request = request(org.springframework.http.HttpMethod.valueOf(method), "/manager-panel/api" + path)
                .header("X-Panel-Key", "panel-secret");
        if (body != null) {
            request.contentType("application/json").content(body);
        }
        return mvc.perform(request);
    }
}
