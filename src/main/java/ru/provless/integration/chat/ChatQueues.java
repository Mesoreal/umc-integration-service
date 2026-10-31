package ru.provless.integration.chat;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queues of the app chat connector. {@code chat.outbound} and {@code chat.inbound} belong to chat-service and are
 * declared here with exactly its arguments (RabbitMQ refuses a redeclaration with others):
 * {@code chat.outbound} plain durable, {@code chat.inbound} dead-lettered to {@code chat.inbound.dead}.
 * <ul>
 *   <li>{@code chat.outbound.dead} — events the connector could not apply even after Chatwoot answered (4xx, bad JSON);
 *       the connector moves them there itself, since chat.outbound has no dead-letter arguments;</li>
 *   <li>{@code integration.chatwoot-app-events} — the app inbox's webhooks, taken in at once and processed in order.</li>
 * </ul>
 */
@Configuration
public class ChatQueues {

    public static final String OUTBOUND = "chat.outbound";
    public static final String OUTBOUND_DEAD = "chat.outbound.dead";
    public static final String INBOUND = "chat.inbound";
    public static final String INBOUND_DEAD = "chat.inbound.dead";
    public static final String WEBHOOK_EVENTS = "integration.chatwoot-app-events";
    public static final String WEBHOOK_EVENTS_DEAD = WEBHOOK_EVENTS + ".dlq";

    @Bean
    Queue chatOutbound() {
        return QueueBuilder.durable(OUTBOUND).build();
    }

    @Bean
    Queue chatOutboundDead() {
        return QueueBuilder.durable(OUTBOUND_DEAD).build();
    }

    @Bean
    Queue chatInbound() {
        return QueueBuilder.durable(INBOUND).deadLetterExchange("").deadLetterRoutingKey(INBOUND_DEAD).build();
    }

    @Bean
    Queue chatInboundDead() {
        return QueueBuilder.durable(INBOUND_DEAD).build();
    }

    @Bean
    Queue chatwootAppEvents() {
        return QueueBuilder.durable(WEBHOOK_EVENTS).deadLetterExchange("").deadLetterRoutingKey(WEBHOOK_EVENTS_DEAD).build();
    }

    @Bean
    Queue chatwootAppEventsDead() {
        return QueueBuilder.durable(WEBHOOK_EVENTS_DEAD).build();
    }
}
