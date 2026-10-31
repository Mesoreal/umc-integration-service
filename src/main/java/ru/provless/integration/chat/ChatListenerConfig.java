package ru.provless.integration.chat;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Listener containers of the chat connector: one consumer, one message in flight — events of a queue are applied in
 * order. No retry advice and no dead-lettering from the container (unlike the service default in application.yml):
 * the listeners retry and set aside events themselves; anything that still escapes is put back in the queue.
 */
@Configuration
public class ChatListenerConfig {

    public static final String ORDERED = "chatOrderedListenerFactory";

    @Bean(ORDERED)
    SimpleRabbitListenerContainerFactory chatOrderedListenerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(true);
        return factory;
    }
}
