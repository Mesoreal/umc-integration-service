package ru.provless.integration.chatwoot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * «Входящие - Приложение» — the inbox new app requests arrive in (plan-chat-departments.md); the app is one more channel
 * of a Chatwoot contact, next to the messenger bridges' inboxes. Taken from config if set, otherwise found by name; an
 * inbox still under an earlier name («Приложение», …) is renamed (history and contacts stay); created once if none.
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

    /** Earlier names of the inbox, comma-separated: such an inbox is renamed to {@link #inboxName}. */
    @Value("${chatwoot.app-inbox-legacy-name:}")
    private String legacyName;

    private volatile Long inboxId;
    private volatile String identifier;

    public String name() {
        return inboxName;
    }

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

    /** An inbox under one of the earlier names, renamed to the current one (its history and contacts stay). */
    private java.util.Optional<Long> renameLegacy() {
        if (legacyName == null || legacyName.isBlank()) {
            return java.util.Optional.empty();
        }
        for (String name : legacyName.split(",")) {
            String old = name.trim();
            java.util.Optional<Long> found = old.isEmpty() ? java.util.Optional.empty() : chatwootClient.findInboxIdByName(old);
            if (found.isPresent()) {
                chatwootClient.renameInbox(found.get(), inboxName);
                log.info("Chatwoot app inbox {} renamed '{}' → '{}'", found.get(), old, inboxName);
                return found;
            }
        }
        return java.util.Optional.empty();
    }

    private long resolve() {
        if (configuredInboxId != null && !configuredInboxId.isBlank()) {
            return Long.parseLong(configuredInboxId.trim());
        }
        return chatwootClient.findInboxIdByName(inboxName)
                .or(this::renameLegacy)
                .orElseGet(() -> {
                    long created = chatwootClient.createApiInbox(inboxName);
                    log.info("Created Chatwoot app inbox '{}' id={}", inboxName, created);
                    return created;
                });
    }
}
