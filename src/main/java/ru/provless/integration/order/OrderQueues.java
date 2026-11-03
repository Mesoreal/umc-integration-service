package ru.provless.integration.order;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** This service's queue on catalog-service's fanout {@code orders}: the copy in Directus; a failed event goes to the DLQ. */
@Configuration
public class OrderQueues {

    public static final String EXCHANGE = "orders";
    public static final String QUEUE = "integration.orders";

    @Bean
    FanoutExchange ordersExchange() {
        return new FanoutExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue integrationOrders() {
        return QueueBuilder.durable(QUEUE).deadLetterExchange("").deadLetterRoutingKey(QUEUE + ".dlq").build();
    }

    @Bean
    Queue integrationOrdersDlq() {
        return QueueBuilder.durable(QUEUE + ".dlq").build();
    }

    @Bean
    Binding integrationOrdersBinding() {
        return BindingBuilder.bind(integrationOrders()).to(ordersExchange());
    }
}
