package ru.provless.integration.order;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import ru.provless.integration.chat.ChatConnectorTestBase;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/** The client's own cancellation reaches the managers as a note in the current request; the managers' changes do not. */
class ClientOrderNotesIT extends ChatConnectorTestBase {

    @Autowired
    ClientOrderNotes notes;

    @Test
    void clientCancelled_noted_managersChange_not() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO chatwoot.chat_conversation (conversation_id, user_id, contact_id, inbox_id, status, current) "
                + "VALUES (142, ?, 42, 6, 'open', true)", user);
        chatwoot.stubFor(post(ACCOUNT + "/conversations/142/messages").willReturn(okJson("{\"id\":900,\"created_at\":1793490000}")));

        notes.onOrderEvent(event(user, "cancelled", "client"));
        notes.onOrderEvent(event(user, "cancelled", "Пётр"));
        notes.onOrderEvent(event(user, "processing", "client"));

        chatwoot.verify(1, postRequestedFor(urlPathMatching(ACCOUNT + "/conversations/142/messages")));
        chatwoot.verify(postRequestedFor(urlEqualTo(ACCOUNT + "/conversations/142/messages"))
                .withRequestBody(matchingJsonPath("$.private", equalTo("true")))
                .withRequestBody(containing("Клиент отменил заказ № 1002 в приложении")));
    }

    private static Message event(UUID user, String status, String by) {
        String json = """
                {"type":"order.status_changed","order_id":"%s","number":1002,"user_id":"%s","status":"%s","changed_by":"%s"}"""
                .formatted(UUID.randomUUID(), user, status, by);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
    }
}
