package ru.provless.integration.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

class ChatOutboundIT extends ChatConnectorTestBase {

    private UUID user;

    @BeforeEach
    void setUp() {
        user = UUID.randomUUID();
        when(appContacts.ensure(any(), isNull())).thenReturn(42L);
        chatwoot.stubFor(get(ACCOUNT + "/inboxes/5").willReturn(okJson("{\"id\":5,\"inbox_identifier\":\"appIdent\"}")));
        drain(ChatQueues.INBOUND);
        drain(ChatQueues.OUTBOUND_DEAD);
    }

    @Test
    void firstMessage_opensRequestAndConfirms() {
        stubNewConversation(15);
        stubMessage(15, 301);
        UUID messageId = UUID.randomUUID();

        outbound(created(messageId, "Нужен счёт"));

        List<String> got = receiveUntil(ChatQueues.INBOUND, b -> b.contains("client.message_delivered"), 10_000);
        assertThat(got.getFirst()).contains("\"type\":\"manager.changed\"").contains("\"manager\":null");
        assertThat(got.getLast()).contains("\"message_id\":\"" + messageId + "\"").contains("\"external_id\":\"cw:301\"");
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations"))
                .withRequestBody(equalToJson("{\"inbox_id\":5,\"contact_id\":42,\"source_id\":\"" + user + "\"}")));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/15/messages"))
                .withRequestBody(matchingJsonPath("$.message_type", equalTo("incoming")))
                .withRequestBody(matchingJsonPath("$.source_id", equalTo("app:" + messageId))));
    }

    @Test
    void openRequest_reused_resolvedRequest_replaced() {
        stubNewConversation(15);
        stubMessage(15, 301);
        outbound(created(UUID.randomUUID(), "раз"));
        receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:301"), 10_000);

        chatwoot.stubFor(get(ACCOUNT + "/conversations/15").willReturn(okJson(conversation(15, "pending"))));
        stubMessage(15, 302);
        outbound(created(UUID.randomUUID(), "два"));
        receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:302"), 10_000);
        chatwoot.verify(1, postRequestedFor(urlEqualTo(ACCOUNT + "/conversations")));

        // the agent resolved it: the next message is a new request
        chatwoot.stubFor(get(ACCOUNT + "/conversations/15").willReturn(okJson(conversation(15, "resolved"))));
        stubNewConversation(16);
        stubMessage(16, 303);
        outbound(created(UUID.randomUUID(), "три"));
        receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:303"), 10_000);
        chatwoot.verify(2, postRequestedFor(urlEqualTo(ACCOUNT + "/conversations")));
        assertThat(jdbc.queryForObject("SELECT conversation_id FROM chatwoot.chat_conversation WHERE user_id = ? AND current",
                Long.class, user)).isEqualTo(16L);
    }

    @Test
    void repeatedEvent_postedOnce() {
        stubNewConversation(15);
        stubMessage(15, 301);
        UUID messageId = UUID.randomUUID();
        outbound(created(messageId, "раз"));
        receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:301"), 10_000);
        chatwoot.stubFor(get(ACCOUNT + "/conversations/15").willReturn(okJson(conversation(15, "open"))));

        outbound(created(messageId, "раз"));
        receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:301"), 10_000);
        chatwoot.verify(1, postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/15/messages")));
    }

    @Test
    void redeliveredAfterCrash_foundInChatwoot_notPostedAgain() {
        stubNewConversation(15);
        UUID messageId = UUID.randomUUID();
        // posted before the crash, the link was never written
        chatwoot.stubFor(get(urlPathEqualTo(ACCOUNT + "/conversations/15/messages")).willReturn(okJson(
                "{\"payload\":[{\"id\":299,\"source_id\":\"app:" + messageId + "\",\"created_at\":1793480000}]}")));
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setRedelivered(true);
        // the broker sets redelivered itself; a fresh publish can't carry it, so force the path through a failing first try
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages").inScenario("crash").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(502)).willSetStateTo("down"));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages").inScenario("crash").whenScenarioStateIs("down")
                .willReturn(okJson("{\"id\":999,\"created_at\":1793480001}")));
        chatwoot.stubFor(get(ACCOUNT + "/conversations/15").willReturn(okJson(conversation(15, "open"))));

        rabbit.send("", ChatQueues.OUTBOUND, new Message(created(messageId, "раз").getBytes(StandardCharsets.UTF_8), props));

        List<String> got = receiveUntil(ChatQueues.INBOUND, b -> b.contains("client.message_delivered"), 10_000);
        assertThat(got.getLast()).contains("cw:299");
        chatwoot.verify(1, postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/15/messages")));
    }

    @Test
    void attachment_fromBucketToChatwoot() {
        stubNewConversation(15);
        putFile("app-chat/" + user + "/f1", "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages")
                .withMultipartRequestBody(aMultipart().withName("attachments[]").withBody(equalTo("%PDF-1.4")))
                .willReturn(okJson("{\"id\":310,\"created_at\":1793480000}")));

        outbound("""
                {"type":"message.created","event_id":"%s","user_id":"%s","message_id":"%s","seq":1,"text":null,
                 "attachments":[{"file_id":"%s","name":"Счёт.pdf","mime_type":"application/pdf","size":8,
                                 "bucket":"%s","object_key":"app-chat/%s/f1"}],
                 "reply_to_id":null,"reply_to_external_id":null,"created_at":"2026-10-31T10:00:00Z"}
                """.formatted(UUID.randomUUID(), user, UUID.randomUUID(), UUID.randomUUID(), BUCKET, user));

        receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:310"), 10_000);
    }

    @Test
    void editAndDelete_privateNotesUnderTheOriginal() {
        stubNewConversation(15);
        stubMessage(15, 301);
        UUID messageId = UUID.randomUUID();
        outbound(created(messageId, "черновик"));
        receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:301"), 10_000);
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages")
                .withRequestBody(matchingJsonPath("$.private", equalTo("true")))
                .willReturn(okJson("{\"id\":400,\"created_at\":1793480000}")));

        outbound("""
                {"type":"message.edited","event_id":"%s","user_id":"%s","message_id":"%s","external_id":"cw:301",
                 "text":"исправлено","previous_text":"черновик"}
                """.formatted(UUID.randomUUID(), user, messageId));
        outbound("""
                {"type":"message.deleted","event_id":"%s","user_id":"%s","message_id":"%s","external_id":"cw:301"}
                """.formatted(UUID.randomUUID(), user, messageId));

        awaitRequests(2, () -> chatwoot.findAll(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/15/messages"))
                .withRequestBody(matchingJsonPath("$.private", equalTo("true")))).size());
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/15/messages"))
                .withRequestBody(equalToJson("""
                        {"content":"✏️ Клиент изменил сообщение:\\nисправлено","message_type":"outgoing","private":true,
                         "content_attributes":{"in_reply_to":301}}""")));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/15/messages"))
                .withRequestBody(equalToJson("""
                        {"content":"❌ Клиент удалил это сообщение","message_type":"outgoing","private":true,
                         "content_attributes":{"in_reply_to":301}}""")));
    }

    @Test
    void chatwootDown_waitsAndKeepsOrder() {
        stubNewConversation(15);
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages").inScenario("down").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("still down"));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages").inScenario("down").whenScenarioStateIs("still down")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("up"));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages").inScenario("down").whenScenarioStateIs("up")
                .withRequestBody(matchingJsonPath("$.content", equalTo("первое")))
                .willReturn(okJson("{\"id\":501,\"created_at\":1793480000}")).willSetStateTo("second"));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages").inScenario("down").whenScenarioStateIs("second")
                .withRequestBody(matchingJsonPath("$.content", equalTo("второе")))
                .willReturn(okJson("{\"id\":502,\"created_at\":1793480001}")));
        chatwoot.stubFor(get(ACCOUNT + "/conversations/15").willReturn(okJson(conversation(15, "open"))));
        chatwoot.stubFor(get(urlPathEqualTo(ACCOUNT + "/conversations/15/messages")).willReturn(okJson("{\"payload\":[]}")));

        outbound(created(UUID.randomUUID(), "первое"));
        outbound(created(UUID.randomUUID(), "второе"));

        List<String> got = receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:502"), 10_000);
        assertThat(String.join("\n", got).indexOf("cw:501")).isLessThan(String.join("\n", got).indexOf("cw:502"));
        assertThat(rabbit.receive(ChatQueues.OUTBOUND_DEAD, 200)).isNull();
    }

    @Test
    void rejectedByChatwoot_setAside_queueGoesOn() {
        stubNewConversation(15);
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages")
                .withRequestBody(matchingJsonPath("$.content", equalTo("плохое")))
                .willReturn(aResponse().withStatus(422).withBody("{\"message\":\"invalid\"}")));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages")
                .withRequestBody(matchingJsonPath("$.content", equalTo("хорошее")))
                .willReturn(okJson("{\"id\":601,\"created_at\":1793480000}")));
        chatwoot.stubFor(get(ACCOUNT + "/conversations/15").willReturn(okJson(conversation(15, "open"))));

        outbound(created(UUID.randomUUID(), "плохое"));
        outbound("not json at all");
        outbound(created(UUID.randomUUID(), "хорошее"));

        receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:601"), 10_000);
        assertThat(rabbit.receive(ChatQueues.OUTBOUND_DEAD, 2000)).isNotNull();
        assertThat(rabbit.receive(ChatQueues.OUTBOUND_DEAD, 2000)).isNotNull();
    }

    @Test
    void readAndDelivered() {
        stubNewConversation(15);
        stubMessage(15, 301);
        outbound(created(UUID.randomUUID(), "привет"));
        receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:301"), 10_000);
        // two managers' messages the connector forwarded (step 3 writes these links)
        jdbc.update("INSERT INTO chatwoot.chat_message_link (cw_message_id, user_id, conversation_id, direction, cw_created_at) "
                + "VALUES (310, ?, 15, 'manager', now()), (311, ?, 15, 'manager', now()), (312, ?, 15, 'manager', now())",
                user, user, user);
        chatwoot.stubFor(patch(urlPathEqualTo(ACCOUNT + "/conversations/15/messages/310")).willReturn(okJson("{}")));
        chatwoot.stubFor(patch(urlPathEqualTo(ACCOUNT + "/conversations/15/messages/311")).willReturn(okJson("{}")));
        String seen = "/public/api/v1/inboxes/appIdent/contacts/" + user + "/conversations/15/update_last_seen";
        chatwoot.stubFor(post(seen).willReturn(aResponse().withStatus(200)));

        outbound("""
                {"type":"client.delivered","event_id":"%s","user_id":"%s","up_to_seq":3,"up_to_external_id":"cw:311"}
                """.formatted(UUID.randomUUID(), user));
        outbound("""
                {"type":"client.read","event_id":"%s","user_id":"%s","up_to_seq":4,"up_to_external_id":"cw:312"}
                """.formatted(UUID.randomUUID(), user));

        awaitRequests(1, () -> chatwoot.findAll(postRequestedFor(urlEqualTo(seen))).size());
        chatwoot.verify(patchRequestedFor(urlEqualTo(ACCOUNT + "/conversations/15/messages/310"))
                .withRequestBody(equalToJson("{\"status\":\"delivered\"}")));
        chatwoot.verify(patchRequestedFor(urlEqualTo(ACCOUNT + "/conversations/15/messages/311")));
        // 312 is covered by read: flagged without a separate call
        chatwoot.verify(0, patchRequestedFor(urlEqualTo(ACCOUNT + "/conversations/15/messages/312")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chatwoot.chat_message_link WHERE user_id = ? "
                + "AND direction = 'manager' AND delivered_to_client", Integer.class, user)).isEqualTo(3);
    }

    @Test
    void typing_onlyWithARequest() {
        String typing = "/public/api/v1/inboxes/appIdent/contacts/" + user + "/conversations/15/toggle_typing";
        chatwoot.stubFor(post(typing).willReturn(aResponse().withStatus(200)));
        outbound("{\"type\":\"client.typing\",\"user_id\":\"%s\",\"is_typing\":true}".formatted(user));

        stubNewConversation(15);
        stubMessage(15, 301);
        outbound(created(UUID.randomUUID(), "привет"));
        receiveUntil(ChatQueues.INBOUND, b -> b.contains("cw:301"), 10_000);
        outbound("{\"type\":\"client.typing\",\"user_id\":\"%s\",\"is_typing\":true}".formatted(user));

        awaitRequests(1, () -> chatwoot.findAll(postRequestedFor(urlEqualTo(typing))).size());
        chatwoot.verify(1, postRequestedFor(urlEqualTo(typing)).withRequestBody(equalToJson("{\"typing_status\":\"on\"}")));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private String created(UUID messageId, String text) {
        return """
                {"type":"message.created","event_id":"%s","user_id":"%s","message_id":"%s","seq":1,"text":"%s",
                 "attachments":[],"reply_to_id":null,"reply_to_external_id":null,"created_at":"2026-10-31T10:00:00Z"}
                """.formatted(UUID.randomUUID(), user, messageId, text);
    }

    private static String conversation(long id, String status) {
        return "{\"meta\":{\"sender\":{\"id\":42}},\"id\":" + id + ",\"inbox_id\":5,\"status\":\"" + status
                + "\",\"agent_last_seen_at\":0}";
    }

    private static void stubNewConversation(long id) {
        chatwoot.stubFor(post(ACCOUNT + "/conversations").willReturn(okJson(conversation(id, "open"))));
    }

    private static void stubMessage(long conversation, long id) {
        chatwoot.stubFor(post(ACCOUNT + "/conversations/" + conversation + "/messages")
                .willReturn(okJson("{\"id\":" + id + ",\"created_at\":1793480000}")));
    }

    private static void awaitRequests(int count, java.util.function.IntSupplier seen) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && seen.getAsInt() < count) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertThat(seen.getAsInt()).isGreaterThanOrEqualTo(count);
    }
}
