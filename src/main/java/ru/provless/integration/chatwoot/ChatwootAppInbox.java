package ru.provless.integration.chatwoot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * «Приложение — входящие» — the inbox new app requests arrive in (plan-chat-departments.md); the app is one more channel
 * of a Chatwoot contact, next to the messenger bridges' inboxes. Taken from config if set, otherwise found by name; an
 * inbox still under the old name «Приложение» is renamed (history and contacts stay); created once if there is none.
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

    /** The name before departments (31.10.2026): such an inbox is renamed to {@link #inboxName}. */
    @Value("${chatwoot.app-inbox-legacy-name:}")
    private String legacyName;

    private volatile Long inboxId;
    private volatile String identifier;

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

    /** The inbox's channel identifier (key of its public API), fetched once. */
    public String identifier() {
        String cached = identifier;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (identifier == null) {
                identifier = chatwootClient.findInboxIdentifier(id());
            }
            return identifier;
        }
    }

    private long resolve() {
        if (configuredInboxId != null && !configuredInboxId.isBlank()) {
            return Long.parseLong(configuredInboxId.trim());
        }
        return chatwootClient.findInboxIdByName(inboxName)
                .or(() -> legacyName == null || legacyName.isBlank() ? java.util.Optional.empty()
                        : chatwootClient.findInboxIdByName(legacyName).map(id -> {
                            chatwootClient.renameInbox(id, inboxName);
                            log.info("Chatwoot app inbox {} renamed '{}' → '{}'", id, legacyName, inboxName);
                            return id;
                        }))
                .orElseGet(() -> {
                    long created = chatwootClient.createApiInbox(inboxName);
                    log.info("Created Chatwoot app inbox '{}' id={}", inboxName, created);
                    return created;
                });
    }
}
