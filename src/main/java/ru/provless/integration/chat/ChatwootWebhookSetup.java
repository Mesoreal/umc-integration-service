package ru.provless.integration.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the webhooks of the app's inboxes («Входящие» and the departments', created if missing) pointed at this service: shortly after start and then every few minutes (a Chatwoot
 * reinstall or a manual edit is repaired by itself). Nothing to do by hand in Chatwoot.
 */
@Slf4j
@Component
public class ChatwootWebhookSetup {

    private final ChatInboxes inboxes;
    private final boolean enabled;
    private final String webhookUrl;

    public ChatwootWebhookSetup(ChatInboxes inboxes, @Value("${chatwoot.enabled:false}") boolean enabled,
                                @Value("${chatwoot.chat.webhook-url}") String webhookUrl) {
        this.inboxes = inboxes;
        this.enabled = enabled;
        this.webhookUrl = webhookUrl;
    }

    @Scheduled(initialDelayString = "${chatwoot.chat.poll-initial-delay}", fixedDelayString = "PT10M")
    public void ensure() {
        if (!enabled) {
            return;
        }
        try {
            for (long inboxId : inboxes.all()) {
                inboxes.ensureWebhook(inboxId, webhookUrl);
            }
        } catch (RuntimeException e) {
            log.warn("Chatwoot app inbox webhooks not checked, next try in 10 min: {}", e.getMessage());
        }
    }
}
