package ru.provless.integration.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import ru.provless.integration.chat.ChatConversations;
import ru.provless.integration.chatwoot.ChatwootClient;

import java.io.IOException;
import java.util.UUID;

/**
 * What the client did to an order themselves reaches the managers as a note in their current request: today — a cancellation from
 * the app (the panel shows it too, but only when opened). The managers' own changes are noted by the panel.
 */
@Slf4j
@Component
public class ClientOrderNotes {

    static final String QUEUE = "integration.order-notes";

    private final ChatConversations conversations;
    private final ChatwootClient chatwoot;
    private final boolean enabled;
    private final ObjectMapper json = new ObjectMapper();

    public ClientOrderNotes(ChatConversations conversations, ChatwootClient chatwoot,
                            @Value("${chatwoot.enabled:false}") boolean enabled) {
        this.conversations = conversations;
        this.chatwoot = chatwoot;
        this.enabled = enabled;
    }

    @RabbitListener(queues = QUEUE)
    public void onOrderEvent(Message message) {
        JsonNode event;
        try {
            event = json.readTree(message.getBody());
        } catch (IOException e) {
            throw new AmqpRejectAndDontRequeueException("Unreadable order event", e);
        }
        if (!enabled || !"order.status_changed".equals(event.path("type").asText())
                || !"client".equals(event.path("changed_by").asText()) || !"cancelled".equals(event.path("status").asText())) {
            return;
        }
        UUID userId = UUID.fromString(event.path("user_id").asText());
        long number = event.path("number").asLong();
        conversations.existing(userId).ifPresentOrElse(
                request -> chatwoot.createPrivateNote(request.getConversationId(),
                        "❌ Клиент отменил заказ № " + number + " в приложении", null),
                () -> log.info("Order {} cancelled by userId={}: no request to note it in", number, userId));
    }

    @Configuration
    static class Queues {
        @Bean
        Queue integrationOrderNotes() {
            return QueueBuilder.durable(QUEUE).deadLetterExchange("").deadLetterRoutingKey(QUEUE + ".dlq").build();
        }

        @Bean
        Queue integrationOrderNotesDlq() {
            return QueueBuilder.durable(QUEUE + ".dlq").build();
        }

        @Bean
        Binding integrationOrderNotesBinding(FanoutExchange ordersExchange) {
            return BindingBuilder.bind(integrationOrderNotes()).to(ordersExchange);
        }
    }
}
