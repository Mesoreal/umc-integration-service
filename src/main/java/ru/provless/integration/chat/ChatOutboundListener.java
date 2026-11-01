package ru.provless.integration.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * {@code chat.outbound}: the user's actions → Chatwoot, strictly in order, retried as {@link OrderedRetry} says. An
 * event given up is set aside in {@code chat.outbound.dead} (that queue of chat-service has no dead-letter arguments).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatOutboundListener {

    private final ChatOutboundHandler handler;
    private final ChatInboundPublisher publisher;
    private final OrderedRetry retry;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @RabbitListener(queues = ChatQueues.OUTBOUND, containerFactory = ChatListenerConfig.ORDERED,
            autoStartup = "${chatwoot.enabled:false}")
    public void onMessage(Message message) {
        OutboundEvent event;
        try {
            event = json.readValue(message.getBody(), OutboundEvent.class);
        } catch (IOException e) {
            setAside(message, "unreadable JSON: " + e.getMessage());
            return;
        }
        boolean redelivered = Boolean.TRUE.equals(message.getMessageProperties().getRedelivered());
        String what = "chat.outbound " + event.type() + " " + event.eventId() + " of userId=" + event.userId();
        if (!retry.run(what, again -> handler.handle(event, redelivered || again))) {
            setAside(message, what);
        }
    }

    private void setAside(Message message, String reason) {
        log.error("chat.outbound event set aside to {}: {}", ChatQueues.OUTBOUND_DEAD, reason);
        publisher.deadOutbound(message);
    }
}
