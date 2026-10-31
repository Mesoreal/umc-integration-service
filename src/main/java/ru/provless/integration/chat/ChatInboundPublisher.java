package ru.provless.integration.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Sends to {@code chat.inbound} as plain JSON (no {@code __TypeId__}: chat-service dispatches by {@code type}),
 * persistent. Times as ISO-8601 strings.
 */
@Component
public class ChatInboundPublisher {

    private final RabbitTemplate rabbit;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public ChatInboundPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public void send(Object event) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        try {
            rabbit.send("", ChatQueues.INBOUND, new Message(json.writeValueAsBytes(event), props));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Inbound event not serialisable: " + event, e);
        }
    }

    /** Puts an event that can't be applied aside, as it came. */
    public void deadOutbound(Message original) {
        rabbit.send("", ChatQueues.OUTBOUND_DEAD, original);
    }
}
