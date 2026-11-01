package ru.provless.integration.chat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A department label moves the request: new conversation in the department's inbox, copies, the old one deleted. */
class ChatTransferIT extends ChatConnectorTestBase {

    @Autowired
    WebApplicationContext context;

    private MockMvc mvc;
    private UUID user;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        user = UUID.randomUUID();
        when(appContacts.ensure(any(), isNull())).thenReturn(42L);
        chatwoot.stubFor(get(ACCOUNT + "/inboxes/5").willReturn(okJson("""
                {"id":5,"inbox_identifier":"intake","secret":"secret5","working_hours_enabled":false,"working_hours":[]}""")));
        drain(ChatQueues.INBOUND);
        // the request in «Входящие»: the user's message 301 and the agent's answer 302 are already in the app
        jdbc.update("INSERT INTO chatwoot.chat_conversation (conversation_id, user_id, contact_id, inbox_id, status, current) "
                + "VALUES (15, ?, 42, 5, 'open', true)", user);
        jdbc.update("INSERT INTO chatwoot.chat_message_link (cw_message_id, external_cw_id, message_id, user_id, conversation_id, "
                + "direction, cw_created_at) VALUES (301, 301, ?, ?, 15, 'client', now()), (302, 302, null, ?, 15, 'manager', now())",
                UUID.randomUUID(), user, user);

        chatwoot.stubFor(post(ACCOUNT + "/contacts/42/contact_inboxes").willReturn(okJson("{}")));
        chatwoot.stubFor(post(ACCOUNT + "/conversations").withRequestBody(matchingJsonPath("$.inbox_id", equalTo("6")))
                .willReturn(okJson("{\"id\":30,\"inbox_id\":6,\"status\":\"open\",\"meta\":{}}")));
        chatwoot.stubFor(get(urlPathEqualTo(ACCOUNT + "/conversations/15/messages")).willReturn(okJson("""
                {"payload":[
                  {"id":300,"message_type":2,"content":"Анна взяла диалог","created_at":1793480000,"private":false},
                  {"id":301,"message_type":0,"content":"Нужен счёт","created_at":1793480100,"private":false,
                   "sender":{"name":"Клиент","type":"contact"}},
                  {"id":302,"message_type":1,"content":"Уточню в продажах","created_at":1793480160,"private":false,
                   "content_attributes":{"in_reply_to":301},"sender":{"name":"Anna","available_name":"Анна","type":"user"}},
                  {"id":303,"message_type":1,"content":"клиент оптовый","created_at":1793480170,"private":true,
                   "sender":{"name":"Anna","available_name":"Анна","type":"user"}}]}""")));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/30/messages").withRequestBody(matchingJsonPath("$.private", equalTo("true")))
                .withRequestBody(containing("Передано из"))
                .willReturn(okJson("{\"id\":400,\"created_at\":1793490000}")));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/30/messages").withRequestBody(containing("copy:301"))
                .willReturn(okJson("{\"id\":401,\"created_at\":1793490001}")));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/30/messages").withRequestBody(containing("copy:302"))
                .willReturn(okJson("{\"id\":402,\"created_at\":1793490002}")));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/30/messages").withRequestBody(containing("copy:303"))
                .willReturn(okJson("{\"id\":403,\"created_at\":1793490003}")));
        chatwoot.stubFor(delete(urlEqualTo(ACCOUNT + "/conversations/15")).willReturn(aResponse().withStatus(200)));
        chatwoot.stubFor(get(ACCOUNT + "/conversations/30").willReturn(okJson("""
                {"id":30,"inbox_id":6,"status":"open","meta":{"assignee_type":"User",
                 "assignee":{"id":8,"name":"Oleg","available_name":"Олег"}}}""")));
    }

    @Test
    void label_movesTheRequestToTheDepartment() throws Exception {
        webhook(labelled(15, 5, "в-продажи"), "secret5").andExpect(status().isOk());

        List<String> got = receiveUntil(ChatQueues.INBOUND, b -> b.contains(user.toString()) && b.contains("working_hours.changed"), 15_000);
        String manager = got.stream().filter(b -> b.contains(user.toString()) && b.contains("manager.changed")).findFirst().orElseThrow();
        assertThat((String) JsonPath.read(manager, "$.manager.id")).isEqualTo("cw:8");
        assertThat((String) JsonPath.read(manager, "$.manager.name")).isEqualTo("Олег");

        // copies: the user's message incoming, the answer outgoing quoting the copy, the note a note; the old one gone
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/contacts/42/contact_inboxes"))
                .withRequestBody(equalToJson("{\"inbox_id\":6,\"source_id\":\"" + user + "\"}")));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/30/messages"))
                .withRequestBody(matchingJsonPath("$.source_id", equalTo("copy:301")))
                .withRequestBody(matchingJsonPath("$.message_type", equalTo("incoming")))
                .withRequestBody(matchingJsonPath("$.content", containing("Нужен счёт"))));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/30/messages"))
                .withRequestBody(matchingJsonPath("$.source_id", equalTo("copy:302")))
                .withRequestBody(matchingJsonPath("$.message_type", equalTo("outgoing")))
                .withRequestBody(matchingJsonPath("$.content", containing("· Анна")))
                .withRequestBody(matchingJsonPath("$.content_attributes.in_reply_to", equalTo("401"))));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/30/messages"))
                .withRequestBody(matchingJsonPath("$.source_id", equalTo("copy:303")))
                .withRequestBody(matchingJsonPath("$.private", equalTo("true"))));
        chatwoot.verify(0, postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/30/messages"))
                .withRequestBody(containing("Анна взяла диалог")));
        chatwoot.verify(deleteRequestedFor(urlEqualTo(ACCOUNT + "/conversations/15")));

        // the request is the department's now; links follow the copies, chat-service's ids stay
        assertThat(jdbc.queryForObject("SELECT conversation_id FROM chatwoot.chat_conversation WHERE user_id = ? AND current",
                Long.class, user)).isEqualTo(30L);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT cw_message_id, external_cw_id, conversation_id "
                + "FROM chatwoot.chat_message_link WHERE user_id = ? ORDER BY external_cw_id", user);
        assertThat(rows).extracting(r -> r.get("cw_message_id")).containsExactly(401L, 402L);
        assertThat(rows).extracting(r -> r.get("external_cw_id")).containsExactly(301L, 302L);
        assertThat(rows).extracting(r -> r.get("conversation_id")).containsOnly(30L);

        // the user's next message goes to the department, quoting the old id chat-service knows
        chatwoot.stubFor(post(ACCOUNT + "/conversations/30/messages").withRequestBody(containing("app:"))
                .willReturn(okJson("{\"id\":410,\"created_at\":1793490100}")));
        outbound("""
                {"type":"message.created","event_id":"%s","user_id":"%s","message_id":"%s","seq":3,"text":"Спасибо",
                 "attachments":[],"reply_to_external_id":"cw:302","created_at":"2026-11-01T10:00:00Z"}
                """.formatted(UUID.randomUUID(), user, UUID.randomUUID()));
        receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:410"), 10_000);
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/30/messages"))
                .withRequestBody(matchingJsonPath("$.content", equalTo("Спасибо")))
                .withRequestBody(matchingJsonPath("$.content_attributes.in_reply_to", equalTo("402"))));
    }

    @Test
    void repeatedWebhookAndOwnDepartmentLabel_doNothingMore() throws Exception {
        webhook(labelled(15, 5, "в-продажи"), "secret5").andExpect(status().isOk());
        receiveUntil(ChatQueues.INBOUND, b -> b.contains(user.toString()) && b.contains("working_hours.changed"), 15_000);

        webhook(labelled(15, 5, "в-продажи"), "secret5").andExpect(status().isOk());
        webhook(labelled(30, 6, "в-продажи"), "secret6").andExpect(status().isOk());
        Thread.sleep(1500);
        chatwoot.verify(1, postRequestedFor(urlEqualTo(ACCOUNT + "/conversations")));
        chatwoot.verify(1, deleteRequestedFor(urlEqualTo(ACCOUNT + "/conversations/15")));
    }

    @Test
    void department_passesItOn() throws Exception {
        webhook(labelled(15, 5, "в-продажи"), "secret5").andExpect(status().isOk());
        receiveUntil(ChatQueues.INBOUND, b -> b.contains(user.toString()) && b.contains("working_hours.changed"), 15_000);

        chatwoot.stubFor(post(ACCOUNT + "/conversations").withRequestBody(matchingJsonPath("$.inbox_id", equalTo("7")))
                .willReturn(okJson("{\"id\":31,\"inbox_id\":7,\"status\":\"open\",\"meta\":{}}")));
        chatwoot.stubFor(get(urlPathEqualTo(ACCOUNT + "/conversations/30/messages")).willReturn(okJson("""
                {"payload":[{"id":401,"message_type":0,"content":"🕓 01.11 15:22\\nНужен счёт","created_at":1793490001,
                  "private":false,"source_id":"copy:301"}]}""")));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/31/messages").willReturn(okJson("{\"id\":501,\"created_at\":1793500000}")));
        chatwoot.stubFor(delete(urlEqualTo(ACCOUNT + "/conversations/30")).willReturn(aResponse().withStatus(200)));
        chatwoot.stubFor(get(ACCOUNT + "/conversations/31").willReturn(okJson("{\"id\":31,\"inbox_id\":7,\"status\":\"open\",\"meta\":{}}")));

        webhook(labelled(30, 6, "в-умц"), "secret6").andExpect(status().isOk());

        // the transfer ends with the department's hours; nobody is assigned in УМЦ yet
        List<String> got = receiveUntil(ChatQueues.INBOUND,
                b -> b.contains(user.toString()) && b.contains("working_hours.changed"), 15_000);
        String changed = got.stream().filter(b -> b.contains("manager.changed")).reduce((a, c) -> c).orElseThrow();
        assertThat((Object) JsonPath.read(changed, "$.manager")).isNull();
        assertThat(jdbc.queryForObject("SELECT inbox_id FROM chatwoot.chat_conversation WHERE user_id = ? AND current",
                Long.class, user)).isEqualTo(7L);
        chatwoot.verify(deleteRequestedFor(urlEqualTo(ACCOUNT + "/conversations/30")));
    }

    private String labelled(long conversationId, long inboxId, String label) {
        return """
                {"event":"conversation_updated","id":%d,"inbox_id":%d,"status":"open","labels":["%s"],
                 "contact_inbox":{"source_id":"%s"},"meta":{"sender":{"id":42},"assignee":null},
                 "changed_attributes":[{"label_list":{"previous_value":[],"current_value":["%s"]}}]}
                """.formatted(conversationId, inboxId, label, user, label);
    }

    private ResultActions webhook(String body, String secret) throws Exception {
        long ts = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8)));
        return mvc.perform(MockMvcRequestBuilders.post("/webhooks/chatwoot/app").contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8))
                .header("X-Chatwoot-Timestamp", ts).header("X-Chatwoot-Signature", signature));
    }
}
