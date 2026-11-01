package ru.provless.integration.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.provless.integration.chatwoot.ChatwootAppInbox;

/**
 * Keeps the app inbox's webhook pointed at this service: shortly after start and then every few minutes (a Chatwoot
 * reinstall or a manual edit is repaired by itself). Nothing to do by hand in Chatwoot.
 */
@Slf4j
@Component
public class ChatwootWebhookSetup {

    private final ChatwootAppInbox appInbox;
    private final boolean enabled;
    private final String webhookUrl;

    public ChatwootWebhookSetup(ChatwootAppInbox appInbox, @Value("${chatwoot.enabled:false}") boolean enabled,
                                @Value("${chatwoot.chat.webhook-url}") String webhookUrl) {
        this.appInbox = appInbox;
        this.enabled = enabled;
        this.webhookUrl = webhookUrl;
    }

    @Scheduled(initialDelayString = "${chatwoot.chat.poll-initial-delay}", fixedDelayString = "PT10M")
    public void ensure() {
        if (!enabled) {
            return;
        }
        try {
            appInbox.ensureWebhook(webhookUrl);
        } catch (RuntimeException e) {
            log.warn("Chatwoot app inbox webhook not checked, next try in 10 min: {}", e.getMessage());
        }
    }
}
