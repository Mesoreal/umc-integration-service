package ru.provless.integration.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * The app inbox's webhooks, queued by {@link ChatwootWebhookController}, applied in order and retried as
 * {@link OrderedRetry} says; one given up goes to {@code integration.chatwoot-app-events.dlq}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatwootAppEventsListener {

    private final ChatwootEventsProcessor processor;
    private final OrderedRetry retry;
    private final ObjectMapper json = new ObjectMapper();

    @RabbitListener(queues = ChatQueues.WEBHOOK_EVENTS, containerFactory = ChatListenerConfig.ORDERED,
            autoStartup = "${chatwoot.enabled:false}")
    public void onMessage(Message message) {
        JsonNode event;
        try {
            event = json.readTree(message.getBody());
        } catch (IOException e) {
            throw new AmqpRejectAndDontRequeueException("Unreadable Chatwoot webhook", e);
        }
        String what = "Chatwoot " + event.path("event").asText() + " " + event.path("id").asText();
        if (!retry.run(what, again -> processor.process(event))) {
            throw new AmqpRejectAndDontRequeueException(what + " given up");
        }
    }
}
