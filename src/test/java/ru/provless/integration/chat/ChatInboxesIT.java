package ru.provless.integration.chat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Several app inboxes («Входящие» 5, departments 6 and 7): signatures, the inbox of the request, hours per inbox. */
class ChatInboxesIT extends ChatConnectorTestBase {

    @Autowired
    WebApplicationContext context;
    @Autowired
    ChatwootPolls polls;

    private MockMvc mvc;
    private UUID user;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        user = UUID.randomUUID();
        chatwoot.stubFor(get(ACCOUNT + "/inboxes/5").willReturn(okJson(
                "{\"id\":5,\"inbox_identifier\":\"intake\",\"secret\":\"secret5\",\"working_hours_enabled\":false,\"working_hours\":[]}")));
        drain(ChatQueues.INBOUND);
        // the user's current request is in the sales inbox (6)
        jdbc.update("INSERT INTO chatwoot.chat_conversation (conversation_id, user_id, contact_id, inbox_id, status, current) "
                + "VALUES (20, ?, 42, 6, 'open', true)", user);
    }

    @Test
    void webhook_signedWithItsOwnInboxSecret() throws Exception {
        String fromSales = agentMessage(900, 6);
        webhook(fromSales, "secret6").andExpect(status().isOk());
        receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:900"), 10_000);

        webhook(agentMessage(901, 6), "secret5").andExpect(status().isUnauthorized());
        webhook(agentMessage(902, 99), "secret6").andExpect(status().isUnauthorized());
    }

    @Test
    void connectorsOwnOutgoing_notSentBackToTheApp() throws Exception {
        String copy = agentMessage(910, 6).replace("\"event\":\"message_created\",",
                "\"event\":\"message_created\",\"source_id\":\"copy:" + UUID.randomUUID() + "\",");
        webhook(copy, "secret6").andExpect(status().isOk());
        assertThat(rabbit.receive(ChatQueues.INBOUND, 1500)).isNull();
    }

    @Test
    void readAndTyping_throughTheInboxOfTheRequest() {
        String seen = "/public/api/v1/inboxes/dept6/contacts/" + user + "/conversations/20/update_last_seen";
        chatwoot.stubFor(post(seen).willReturn(aResponse().withStatus(200)));
        outbound("""
                {"type":"client.read","event_id":"%s","user_id":"%s","up_to_seq":1,"up_to_external_id":null}
                """.formatted(UUID.randomUUID(), user));
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && chatwoot.findAll(postRequestedFor(urlEqualTo(seen))).isEmpty()) {
            Thread.onSpinWait();
        }
        chatwoot.verify(postRequestedFor(urlEqualTo(seen)));
    }

    @Test
    void hours_ofTheDepartment_toItsUsers() {
        chatwoot.stubFor(get(ACCOUNT + "/inboxes/6").willReturn(okJson("""
                {"id":6,"inbox_identifier":"dept6","secret":"secret6","working_hours_enabled":true,"timezone":"Europe/Moscow",
                 "working_hours":[{"day_of_week":1,"closed_all_day":false,"open_hour":9,"open_minutes":0,"close_hour":17,"close_minutes":0}]}""")));

        polls.pollHours();

        String hours = receiveUntil(ChatQueues.INBOUND, b -> b.contains(user.toString()), 5000).getLast();
        assertThat((String) JsonPath.read(hours, "$.type")).isEqualTo("working_hours.changed");
        assertThat((String) JsonPath.read(hours, "$.days[0].close")).isEqualTo("17:00");
    }

    private String agentMessage(long id, long inboxId) {
        return """
                {"event":"message_created","id":%d,"message_type":"outgoing","private":false,"content":"ответ отдела",
                 "created_at":"2026-11-01T20:00:00.000Z","sender":{"id":7,"name":"Anna","type":"user"},
                 "conversation":{"id":20,"inbox_id":%d,"status":"open","contact_inbox":{"source_id":"%s"},
                   "meta":{"sender":{"id":42},"assignee":null}}}
                """.formatted(id, inboxId, user);
    }

    private ResultActions webhook(String body, String secret) throws Exception {
        long ts = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8)));
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/webhooks/chatwoot/app").contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8))
                .header("X-Chatwoot-Timestamp", ts).header("X-Chatwoot-Signature", signature));
    }
}
