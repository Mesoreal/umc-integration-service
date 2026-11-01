package ru.provless.integration.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import ru.provless.integration.account.AccountDeletedListener;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/** account.deleted (BE-15, decision 1): the app's conversations go; the contact goes only when the app was its only channel. */
class AccountDeletedIT extends ChatConnectorTestBase {

    @Autowired
    AccountDeletedListener listener;

    private UUID user;

    @BeforeEach
    void setUp() {
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO chatwoot.app_contact_link (user_id, contact_id) VALUES (?, 42)", user);
        jdbc.update("INSERT INTO chatwoot.chat_conversation (conversation_id, user_id, contact_id, inbox_id, status, current) "
                + "VALUES (15, ?, 42, 5, 'open', true)", user);
        jdbc.update("INSERT INTO chatwoot.chat_message_link (cw_message_id, external_cw_id, user_id, conversation_id, direction, "
                + "cw_created_at) VALUES (301, 301, ?, 15, 'client', now())", user);
        // conversations of the contact: two of the app (5 «Входящие», 6 a department), one of Telegram (inbox 2)
        chatwoot.stubFor(get(ACCOUNT + "/contacts/42/conversations").willReturn(okJson("""
                {"payload":[{"id":15,"inbox_id":5},{"id":20,"inbox_id":6},{"id":9,"inbox_id":2}]}""")));
        chatwoot.stubFor(delete(urlMatching(ACCOUNT + "/conversations/\\\\d+")).willReturn(aResponse().withStatus(200)));
        chatwoot.stubFor(delete(urlEqualTo(ACCOUNT + "/contacts/42")).willReturn(aResponse().withStatus(200)));
        chatwoot.stubFor(post(ACCOUNT + "/contacts/42/destroy_custom_attributes").willReturn(okJson("{}")));
    }

    @Test
    void personAlsoInTelegram_appConversationsGo_contactStays() {
        chatwoot.stubFor(get(ACCOUNT + "/contacts/42").willReturn(okJson("""
                {"payload":{"id":42,"contact_inboxes":[{"source_id":"x","inbox":{"id":5}},{"source_id":"y","inbox":{"id":2}}]}}""")));

        listener.onDeleted(event());

        chatwoot.verify(1, deleteRequestedFor(urlEqualTo(ACCOUNT + "/conversations/15")));
        chatwoot.verify(1, deleteRequestedFor(urlEqualTo(ACCOUNT + "/conversations/20")));
        chatwoot.verify(0, deleteRequestedFor(urlEqualTo(ACCOUNT + "/conversations/9")));
        chatwoot.verify(0, deleteRequestedFor(urlEqualTo(ACCOUNT + "/contacts/42")));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/contacts/42/destroy_custom_attributes"))
                .withRequestBody(equalToJson("{\"custom_attributes\":[\"app_user_id\"]}")));
        assertGoneHere();
    }

    @Test
    void onlyTheApp_contactGoes() {
        chatwoot.stubFor(get(ACCOUNT + "/contacts/42").willReturn(okJson("""
                {"payload":{"id":42,"contact_inboxes":[{"source_id":"x","inbox":{"id":5}},{"source_id":"x","inbox":{"id":6}}]}}""")));

        listener.onDeleted(event());
        listener.onDeleted(event());

        chatwoot.verify(1, deleteRequestedFor(urlEqualTo(ACCOUNT + "/contacts/42")));
        assertGoneHere();
    }

    private void assertGoneHere() {
        for (String table : new String[]{"chatwoot.app_contact_link", "chatwoot.chat_conversation", "chatwoot.chat_message_link"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, user)).isZero();
        }
    }

    private Message event() {
        return new Message(("{\"user_id\":\"" + user + "\"}").getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
