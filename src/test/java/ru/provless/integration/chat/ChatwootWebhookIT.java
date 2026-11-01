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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatwootWebhookIT extends ChatConnectorTestBase {

    private static final String SECRET = "inbox-secret";

    @Autowired
    WebApplicationContext context;
    @Autowired
    ChatwootWebhookSetup setup;

    private MockMvc mvc;
    private UUID user;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        user = UUID.randomUUID();
        chatwoot.stubFor(get(ACCOUNT + "/inboxes/5").willReturn(okJson(
                "{\"id\":5,\"inbox_identifier\":\"appIdent\",\"secret\":\"" + SECRET + "\","
                        + "\"webhook_url\":\"http://integration-service:8080/webhooks/chatwoot/app\"}")));
        drain(ChatQueues.INBOUND);
        drain(ChatQueues.WEBHOOK_EVENTS_DEAD);
    }

    @Test
    void unsignedOrStale_refused() throws Exception {
        String body = agentMessage(700, "привет", "");
        mvc.perform(post("/webhooks/chatwoot/app").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        long now = Instant.now().getEpochSecond();
        mvc.perform(post("/webhooks/chatwoot/app").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Chatwoot-Timestamp", now).header("X-Chatwoot-Signature", sign("other", now, body)))
                .andExpect(status().isUnauthorized());
        long old = now - 600;
        mvc.perform(post("/webhooks/chatwoot/app").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Chatwoot-Timestamp", old).header("X-Chatwoot-Signature", sign(SECRET, old, body)))
                .andExpect(status().isUnauthorized());
        assertThat(rabbit.receive(ChatQueues.INBOUND, 1000)).isNull();
    }

    @Test
    void agentReply_withFile_reachesTheApp_once() throws Exception {
        chatwoot.stubFor(get("/rails/active_storage/blobs/redirect/abc/%D0%A1%D1%87%D1%91%D1%82.pdf")
                .willReturn(aResponse().withStatus(302).withHeader("Location", chatwoot.baseUrl() + "/storage/obj1")));
        chatwoot.stubFor(get("/storage/obj1").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/pdf").withBody("%PDF-1.4 счёт")));
        String body = agentMessage(700, "Счёт во вложении", """
                ,"attachments":[{"id":9,"file_type":"file","extension":"pdf","file_size":20,
                  "data_url":"https://app.chatwoot-test.mesoreal.ru/rails/active_storage/blobs/redirect/abc/%D0%A1%D1%87%D1%91%D1%82.pdf",
                  "thumb_url":""}]""");

        webhook(body).andExpect(status().isOk());

        String message = receiveUntil(ChatQueues.INBOUND, b -> b.contains("manager.message"), 10_000).getLast();
        assertThat((String) JsonPath.read(message, "$.user_id")).isEqualTo(user.toString());
        assertThat((String) JsonPath.read(message, "$.external_id")).isEqualTo("cw:700");
        assertThat((String) JsonPath.read(message, "$.author")).isEqualTo("manager");
        assertThat((String) JsonPath.read(message, "$.text")).isEqualTo("Счёт во вложении");
        assertThat((String) JsonPath.read(message, "$.sender.id")).isEqualTo("cw:7");
        assertThat((String) JsonPath.read(message, "$.sender.name")).isEqualTo("Анна");
        assertThat((String) JsonPath.read(message, "$.reply_to_external_id")).isEqualTo("cw:301");
        assertThat((String) JsonPath.read(message, "$.attachments[0].name")).isEqualTo("Счёт.pdf");
        assertThat((String) JsonPath.read(message, "$.attachments[0].mime_type")).isEqualTo("application/pdf");
        String key = JsonPath.read(message, "$.attachments[0].object_key");
        assertThat(key).startsWith("app-chat/" + user + "/");
        assertThat(new String(read(key), StandardCharsets.UTF_8)).isEqualTo("%PDF-1.4 счёт");

        // Chatwoot repeats the webhook: nothing new
        webhook(body).andExpect(status().isOk());
        assertThat(rabbit.receive(ChatQueues.INBOUND, 1500)).isNull();
    }

    @Test
    void notesEchoesAndBots_ignored() throws Exception {
        webhook(message(701, "outgoing", true, "user", "заметка")).andExpect(status().isOk());
        webhook(message(702, "incoming", false, "contact", "эхо нашего")).andExpect(status().isOk());
        webhook(message(703, "outgoing", false, "agent_bot", "бот")).andExpect(status().isOk());
        webhook(message(704, "template", false, "user", "шаблон")).andExpect(status().isOk());
        assertThat(rabbit.receive(ChatQueues.INBOUND, 1500)).isNull();
    }

    @Test
    void agentDeletes_forwardedMessage() throws Exception {
        webhook(agentMessage(710, "ошибся", "")).andExpect(status().isOk());
        receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:710"), 10_000);

        webhook("""
                {"event":"message_updated","id":710,"message_type":"outgoing","private":false,
                 "content":"Сообщение удалено","content_attributes":{"deleted":true},
                 "sender":{"id":7,"name":"Anna","type":"user"},"conversation":%s}
                """.formatted(conversation(15, 7, "online"))).andExpect(status().isOk());

        String deleted = receiveUntil(ChatQueues.INBOUND, b -> b.contains("manager.message_deleted"), 10_000).getLast();
        assertThat((String) JsonPath.read(deleted, "$.external_id")).isEqualTo("cw:710");
    }

    @Test
    void typing_ofAgentsOnly() throws Exception {
        webhook("""
                {"event":"conversation_typing_on","user":{"id":7,"name":"Анна","type":"user"},"is_private":true,
                 "conversation":%s}""".formatted(conversation(15, 7, "online"))).andExpect(status().isOk());
        webhook("""
                {"event":"conversation_typing_on","user":{"id":42,"name":"Клиент","type":"contact"},"is_private":false,
                 "conversation":%s}""".formatted(conversation(15, 7, "online"))).andExpect(status().isOk());
        webhook("""
                {"event":"conversation_typing_on","user":{"id":7,"name":"Анна","type":"user"},"is_private":false,
                 "conversation":%s}""".formatted(conversation(15, 7, "online"))).andExpect(status().isOk());

        List<String> got = receiveUntil(ChatQueues.INBOUND, b -> b.contains("manager.typing"), 10_000);
        assertThat(got).hasSize(1);
        assertThat((Boolean) JsonPath.read(got.getFirst(), "$.is_typing")).isTrue();
        assertThat((String) JsonPath.read(got.getFirst(), "$.name")).isEqualTo("Анна");
    }

    @Test
    void assignee_isTheManager() throws Exception {
        webhook(conversationEvent(conversation(15, 7, "busy"))).andExpect(status().isOk());
        String changed = receiveUntil(ChatQueues.INBOUND, b -> b.contains("manager.changed"), 10_000).getLast();
        assertThat((String) JsonPath.read(changed, "$.manager.id")).isEqualTo("cw:7");
        assertThat((String) JsonPath.read(changed, "$.manager.name")).isEqualTo("Анна");
        assertThat((String) JsonPath.read(changed, "$.manager.status")).isEqualTo("away");
        assertThat((Boolean) JsonPath.read(changed, "$.manager.is_personal")).isFalse();

        // same assignee again: nothing
        webhook(conversationEvent(conversation(15, 7, "online"))).andExpect(status().isOk());
        assertThat(rabbit.receive(ChatQueues.INBOUND, 1500)).isNull();

        // unassigned
        webhook(conversationEvent(conversation(15, null, null))).andExpect(status().isOk());
        String nobody = receiveUntil(ChatQueues.INBOUND, b -> b.contains("manager.changed"), 10_000).getLast();
        assertThat((Object) JsonPath.read(nobody, "$.manager")).isNull();
    }

    @Test
    void setup_pointsTheWebhookAtTheService() {
        chatwoot.stubFor(get(ACCOUNT + "/inboxes/5").willReturn(okJson(
                "{\"id\":5,\"inbox_identifier\":\"appIdent\",\"secret\":\"" + SECRET + "\",\"webhook_url\":\"\"}")));
        chatwoot.stubFor(patch(urlEqualTo(ACCOUNT + "/inboxes/5")).willReturn(okJson("{\"id\":5}")));

        setup.ensure();

        chatwoot.verify(patchRequestedFor(urlEqualTo(ACCOUNT + "/inboxes/5")).withRequestBody(equalToJson(
                "{\"channel\":{\"webhook_url\":\"http://integration-service:8080/webhooks/chatwoot/app\"}}")));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private ResultActions webhook(String body) throws Exception {
        long ts = Instant.now().getEpochSecond();
        return mvc.perform(post("/webhooks/chatwoot/app").contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8))
                .header("X-Chatwoot-Timestamp", ts)
                .header("X-Chatwoot-Signature", sign(SECRET, ts, body)));
    }

    private String agentMessage(long id, String text, String extra) {
        return """
                {"event":"message_created","id":%d,"message_type":"outgoing","private":false,"content":"%s",
                 "content_attributes":{"in_reply_to":301},"created_at":"2026-10-31T20:00:00.000Z",
                 "sender":{"id":7,"name":"Anna Ivanova","email":"a@x","type":"user"},"conversation":%s%s}
                """.formatted(id, text, conversation(15, 7, "online"), extra);
    }

    private String message(long id, String type, boolean isPrivate, String senderType, String text) {
        return """
                {"event":"message_created","id":%d,"message_type":"%s","private":%s,"content":"%s",
                 "sender":{"id":7,"name":"x","type":"%s"},"conversation":%s}
                """.formatted(id, type, isPrivate, text, senderType, conversation(15, 7, "online"));
    }

    private String conversationEvent(String conversation) {
        return "{\"event\":\"conversation_updated\"," + conversation.substring(1);
    }

    private String conversation(long id, Integer assignee, String availability) {
        String assigneeJson = assignee == null ? "null" : """
                {"id":%d,"name":"Anna Ivanova","available_name":"Анна","availability_status":"%s","type":"user"}
                """.formatted(assignee, availability);
        return """
                {"id":%d,"inbox_id":5,"status":"open","contact_inbox":{"source_id":"%s","inbox_id":5},
                 "meta":{"sender":{"id":42,"name":"Клиент"},"assignee":%s,"assignee_type":%s}}
                """.formatted(id, user, assigneeJson, assignee == null ? "null" : "\"User\"");
    }

    private static String sign(String secret, long ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] read(String key) {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(s3Url()))
                .region(Region.of("ru-central1"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("k", "s")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            return s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(key)).asByteArray();
        }
    }
}
