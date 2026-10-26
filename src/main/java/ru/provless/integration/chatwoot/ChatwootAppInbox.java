package ru.provless.integration.chatwoot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The «Приложение» inbox — the app is one more channel of a Chatwoot contact, next to the
 * messenger bridges' inboxes. Taken from config if set, otherwise found by name or created once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatwootAppInbox {

    private final ChatwootClient chatwootClient;

    @Value("${chatwoot.app-inbox-name}")
    private String inboxName;

    @Value("${chatwoot.app-inbox-id:}")
    private String configuredInboxId;

    private volatile Long inboxId;

    public long id() {
        Long cached = inboxId;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (inboxId == null) {
                inboxId = resolve();
            }
            return inboxId;
        }
    }

    private long resolve() {
        if (configuredInboxId != null && !configuredInboxId.isBlank()) {
            return Long.parseLong(configuredInboxId.trim());
        }
        return chatwootClient.findInboxIdByName(inboxName).orElseGet(() -> {
            long created = chatwootClient.createApiInbox(inboxName);
            log.info("Created Chatwoot app inbox '{}' id={}", inboxName, created);
            return created;
        });
    }
}
