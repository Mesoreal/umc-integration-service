package ru.provless.integration.account;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * This service's queue on auth-service's fanout {@code account.deleted} (BE-15). A failure is retried by the listener
 * container (application.yml) and then dead-lettered to the DLQ, like the other queues of this service.
 */
@Configuration
public class AccountDeletedQueues {

    public static final String EXCHANGE = "account.deleted";
    public static final String QUEUE = "integration.account-deleted";

    @Bean
    FanoutExchange accountDeletedExchange() {
        return new FanoutExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue integrationAccountDeleted() {
        return QueueBuilder.durable(QUEUE).deadLetterExchange("").deadLetterRoutingKey(QUEUE + ".dlq").build();
    }

    @Bean
    Queue integrationAccountDeletedDlq() {
        return QueueBuilder.durable(QUEUE + ".dlq").build();
    }

    @Bean
    Binding integrationAccountDeletedBinding() {
        return BindingBuilder.bind(integrationAccountDeleted()).to(accountDeletedExchange());
    }
}
