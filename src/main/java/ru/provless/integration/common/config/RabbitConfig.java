package ru.provless.integration.common.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queues this service consumes. The producers (auth-service, profile-service) declare the main
 * queues with exactly the same arguments — RabbitMQ rejects a redeclaration with different ones.
 */
@Configuration
public class RabbitConfig {

    public static final String USER_LOGGED_IN_QUEUE = "auth.user-logged-in";
    public static final String PROFILE_CONTACTS_UPDATED_QUEUE = "profile.contacts-updated";
    private static final String DLQ_SUFFIX = ".dlq";

    @Bean
    public Queue userLoggedInQueue() {
        return withDeadLetter(USER_LOGGED_IN_QUEUE);
    }

    @Bean
    public Queue userLoggedInDeadLetterQueue() {
        return QueueBuilder.durable(USER_LOGGED_IN_QUEUE + DLQ_SUFFIX).build();
    }

    @Bean
    public Queue profileContactsUpdatedQueue() {
        return withDeadLetter(PROFILE_CONTACTS_UPDATED_QUEUE);
    }

    @Bean
    public Queue profileContactsUpdatedDeadLetterQueue() {
        return QueueBuilder.durable(PROFILE_CONTACTS_UPDATED_QUEUE + DLQ_SUFFIX).build();
    }

    /**
     * Producers run other Jackson/Spring AMQP versions and send their own classes in __TypeId__ —
     * convert to the listener parameter type instead of trusting that header.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }

    private static Queue withDeadLetter(String name) {
        return QueueBuilder.durable(name)
                .deadLetterExchange("")
                .deadLetterRoutingKey(name + DLQ_SUFFIX)
                .build();
    }
}
