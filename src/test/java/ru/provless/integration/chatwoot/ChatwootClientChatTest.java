package ru.provless.integration.chatwoot;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requests and responses as Chatwoot 4.19.1 builds them (controllers, builders, jbuilder views). */
class ChatwootClientChatTest {

    private static final String ACCOUNT = "/api/v1/accounts/1";
    private static WireMockServer chatwoot;
    private ChatwootClient client;

    @BeforeAll
    static void start() {
        chatwoot = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        chatwoot.start();
    }

    @AfterAll
    static void stop() {
        chatwoot.stop();
    }

    @BeforeEach
    void setUp() {
        chatwoot.resetAll();
        client = new ChatwootClient();
        ReflectionTestUtils.setField(client, "baseUrl", chatwoot.baseUrl());
        ReflectionTestUtils.setField(client, "accountIdRaw", "1");
        ReflectionTestUtils.setField(client, "apiToken", "token");
        ReflectionTestUtils.setField(client, "connectTimeoutMs", 1000);
        ReflectionTestUtils.setField(client, "readTimeoutMs", 2000);
        client.init();
    }

    @Test
    void createConversation_parsesDisplayIdAndAssignee() {
        chatwoot.stubFor(post(ACCOUNT + "/conversations")
                .withHeader("api_access_token", equalTo("token"))
                .withRequestBody(equalToJson("{\"inbox_id\":5,\"contact_id\":42,\"source_id\":\"user-1\"}"))
                .willReturn(okJson("""
                        {"meta":{"sender":{"id":42},"channel":"Channel::Api","assignee_type":"User",
                                 "assignee":{"id":7,"name":"Anna Ivanova","available_name":"Анна","availability_status":"online"}},
                         "id":15,"inbox_id":5,"status":"open","agent_last_seen_at":0,"contact_last_seen_at":0}
                        """)));

        ChatwootConversation conversation = client.createConversation(5, 42, "user-1");

        assertThat(conversation.id()).isEqualTo(15);
        assertThat(conversation.status()).isEqualTo("open");
        assertThat(conversation.assigneeId()).isEqualTo(7L);
        assertThat(conversation.assigneeName()).isEqualTo("Анна");
        assertThat(conversation.agentLastSeenAt()).isNull();
        assertThat(conversation.isResolved()).isFalse();
    }

    @Test
    void findConversation_unassignedResolvedAndMissing() {
        chatwoot.stubFor(get(ACCOUNT + "/conversations/15").willReturn(okJson("""
                {"meta":{"sender":{"id":42}},"id":15,"inbox_id":5,"status":"resolved","agent_last_seen_at":1793480000}
                """)));
        chatwoot.stubFor(get(ACCOUNT + "/conversations/16").willReturn(aResponse().withStatus(404)));

        ChatwootConversation conversation = client.findConversation(15).orElseThrow();
        assertThat(conversation.isResolved()).isTrue();
        assertThat(conversation.assigneeId()).isNull();
        assertThat(conversation.agentLastSeenAt()).isEqualTo(Instant.ofEpochSecond(1793480000));
        assertThat(client.findConversation(16)).isEmpty();
    }

    @Test
    void incomingMessage_json() {
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages")
                .withRequestBody(equalToJson("""
                        {"content":"Нужен счёт","message_type":"incoming","private":false,"source_id":"app:m1",
                         "content_attributes":{"in_reply_to":300}}
                        """))
                .willReturn(okJson("{\"id\":301,\"created_at\":1793480100,\"message_type\":0}")));

        ChatwootClient.ChatwootMessageRef ref = client.createIncomingMessage(15, "Нужен счёт", "app:m1", 300L, List.of());

        assertThat(ref.id()).isEqualTo(301);
        assertThat(ref.createdAt()).isEqualTo(Instant.ofEpochSecond(1793480100));
    }

    @Test
    void incomingMessage_withAttachment_multipart() {
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages")
                .withMultipartRequestBody(aMultipart().withName("message_type").withBody(equalTo("incoming")))
                .withMultipartRequestBody(aMultipart().withName("source_id").withBody(equalTo("app:m2")))
                .withMultipartRequestBody(aMultipart().withName("content_attributes").withBody(equalToJson("{\"in_reply_to\":300}")))
                .withMultipartRequestBody(aMultipart().withName("attachments[]")
                        .withHeader("Content-Type", equalTo("application/pdf"))
                        .withHeader("Content-Disposition", com.github.tomakehurst.wiremock.client.WireMock.containing("Счёт.pdf"))
                        .withBody(equalTo("%PDF-1.4")))
                .willReturn(okJson("{\"id\":302,\"created_at\":1793480200}")));

        ChatwootClient.ChatwootMessageRef ref = client.createIncomingMessage(15, null, "app:m2", 300L,
                List.of(new ChatwootUpload("Счёт.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8))));

        assertThat(ref.id()).isEqualTo(302);
    }

    @Test
    void privateNote_andStatus() {
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages")
                .withRequestBody(equalToJson("""
                        {"content":"✏️ Клиент изменил: новый текст","message_type":"outgoing","private":true,
                         "content_attributes":{"in_reply_to":301}}
                        """))
                .willReturn(okJson("{\"id\":303,\"created_at\":1793480300}")));
        chatwoot.stubFor(patch(urlEqualTo(ACCOUNT + "/conversations/15/messages/310")).willReturn(okJson("{\"id\":310}")));

        assertThat(client.createPrivateNote(15, "✏️ Клиент изменил: новый текст", 301L).id()).isEqualTo(303);
        client.updateMessageStatus(15, 310, "delivered");

        chatwoot.verify(patchRequestedFor(urlEqualTo(ACCOUNT + "/conversations/15/messages/310"))
                .withRequestBody(equalToJson("{\"status\":\"delivered\"}")));
    }

    @Test
    void agents() {
        chatwoot.stubFor(get(ACCOUNT + "/agents").willReturn(okJson("""
                [{"id":7,"name":"Anna Ivanova","available_name":"Анна","availability_status":"online","role":"agent"},
                 {"id":8,"name":"Oleg","available_name":null,"availability_status":"busy"}]
                """)));

        assertThat(client.listAgents()).containsExactly(
                new ChatwootAgent(7, "Анна", "online"), new ChatwootAgent(8, "Oleg", "busy"));
    }

    @Test
    void inboxSettings_andWebhook() {
        chatwoot.stubFor(get(ACCOUNT + "/inboxes/5").willReturn(okJson("""
                {"id":5,"name":"Приложение","channel_type":"Channel::Api","webhook_url":"","secret":"s3cr3t",
                 "inbox_identifier":"abcIdent","working_hours_enabled":true,"timezone":"Europe/Moscow",
                 "working_hours":[
                   {"day_of_week":0,"closed_all_day":true,"open_hour":null,"open_minutes":null,"close_hour":null,"close_minutes":null,"open_all_day":false},
                   {"day_of_week":1,"closed_all_day":false,"open_hour":10,"open_minutes":0,"close_hour":19,"close_minutes":30,"open_all_day":false},
                   {"day_of_week":6,"closed_all_day":false,"open_hour":null,"open_minutes":null,"close_hour":null,"close_minutes":null,"open_all_day":true}]}
                """)));
        chatwoot.stubFor(patch(urlEqualTo(ACCOUNT + "/inboxes/5")).willReturn(okJson("{\"id\":5}")));

        ChatwootInboxSettings inbox = client.inboxSettings(5);
        assertThat(inbox.secret()).isEqualTo("s3cr3t");
        assertThat(inbox.identifier()).isEqualTo("abcIdent");
        assertThat(inbox.webhookUrl()).isNull();
        assertThat(inbox.workingHoursEnabled()).isTrue();
        assertThat(inbox.days()).containsExactly(
                new ChatwootInboxSettings.Day(0, null, null),
                new ChatwootInboxSettings.Day(1, "10:00", "19:30"),
                new ChatwootInboxSettings.Day(6, "00:00", "23:59"));

        client.setInboxWebhook(5, "http://integration-service:8080/webhooks/chatwoot/app");
        chatwoot.verify(patchRequestedFor(urlEqualTo(ACCOUNT + "/inboxes/5")).withRequestBody(equalToJson(
                "{\"channel\":{\"webhook_url\":\"http://integration-service:8080/webhooks/chatwoot/app\"}}")));
    }

    @Test
    void publicApi_seenAndTyping() {
        String base = "/public/api/v1/inboxes/abcIdent/contacts/11111111-2222-3333-4444-555555555555/conversations/15";
        chatwoot.stubFor(post(base + "/update_last_seen").willReturn(aResponse().withStatus(200)));
        chatwoot.stubFor(post(base + "/toggle_typing").willReturn(aResponse().withStatus(200)));

        client.markSeenByContact("abcIdent", "11111111-2222-3333-4444-555555555555", 15);
        client.setContactTyping("abcIdent", "11111111-2222-3333-4444-555555555555", 15, true);

        chatwoot.verify(postRequestedFor(urlEqualTo(base + "/update_last_seen")));
        chatwoot.verify(postRequestedFor(urlEqualTo(base + "/toggle_typing"))
                .withRequestBody(equalToJson("{\"typing_status\":\"on\"}")));
    }

    @Test
    void errors_carryChatwootStatus() {
        chatwoot.stubFor(post(ACCOUNT + "/conversations/15/messages").willReturn(aResponse().withStatus(503)));
        chatwoot.stubFor(post(ACCOUNT + "/conversations/16/messages").willReturn(aResponse().withStatus(422)
                .withBody("{\"message\":\"bad\"}")));

        assertThatThrownBy(() -> client.createIncomingMessage(15, "a", "app:x", null, List.of()))
                .isInstanceOfSatisfying(ChatwootException.class, e -> assertThat(e.getHttpStatus()).isEqualTo(503));
        assertThatThrownBy(() -> client.createIncomingMessage(16, "a", "app:x", null, List.of()))
                .isInstanceOfSatisfying(ChatwootException.class, e -> assertThat(e.isUnprocessable()).isTrue());
    }
}
