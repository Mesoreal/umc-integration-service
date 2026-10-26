package ru.provless.integration.chatwoot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import ru.provless.integration.common.config.RabbitConfig;

import java.util.UUID;

/**
 * Keeps the app user's Chatwoot contact in sync. A failure is retried by the listener container
 * (spring.rabbitmq.listener.simple.retry) and then dead-lettered to {@code <queue>.dlq}; the lazy
 * {@code /internal/chatwoot/app-contacts/{userId}/ensure} call repairs anything missed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatwootEventsListener {

    private final AppContactService appContactService;

    /** auth-service, first successful OTP of a phone. */
    public record UserRegisteredEvent(UUID userId, String phone) {
    }

    /** profile-service, after the full name may have changed. */
    public record ProfileContactsUpdatedEvent(UUID userId) {
    }

    @RabbitListener(queues = RabbitConfig.USER_REGISTERED_QUEUE, autoStartup = "${chatwoot.enabled:false}")
    public void onUserRegistered(UserRegisteredEvent event) {
        log.info("auth.user-registered: userId={}", event.userId());
        appContactService.ensure(event.userId(), event.phone());
    }

    @RabbitListener(queues = RabbitConfig.PROFILE_CONTACTS_UPDATED_QUEUE, autoStartup = "${chatwoot.enabled:false}")
    public void onProfileContactsUpdated(ProfileContactsUpdatedEvent event) {
        log.info("profile.contacts-updated: userId={}", event.userId());
        appContactService.ensure(event.userId(), null);
    }
}
