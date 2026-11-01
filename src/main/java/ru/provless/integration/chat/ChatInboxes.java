package ru.provless.integration.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.provless.integration.chatwoot.ChatwootAppInbox;
import ru.provless.integration.chatwoot.ChatwootClient;
import ru.provless.integration.chatwoot.ChatwootInboxSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The app's inboxes: «Входящие» ({@link ChatwootAppInbox}) and one per department. Department inboxes are found by name
 * or created once; their public identifiers and webhook secrets are read from Chatwoot and cached (a secret is read
 * again when a signature does not match — it may have been rotated).
 */
@Slf4j
@Component
public class ChatInboxes {

    private final ChatwootClient chatwoot;
    private final ChatwootAppInbox intake;
    private final ChatDepartments departments;
    private final Map<String, Long> departmentInbox = new ConcurrentHashMap<>();
    private final Map<Long, String> identifiers = new ConcurrentHashMap<>();
    private final Map<Long, String> secrets = new ConcurrentHashMap<>();

    public ChatInboxes(ChatwootClient chatwoot, ChatwootAppInbox intake, ChatDepartments departments) {
        this.chatwoot = chatwoot;
        this.intake = intake;
        this.departments = departments;
    }

    public long intakeId() {
        return intake.id();
    }

    public long departmentInboxId(ChatDepartments.Department department) {
        return departmentInbox.computeIfAbsent(department.key(), key ->
                chatwoot.findInboxIdByName(department.inboxName()).orElseGet(() -> {
                    long created = chatwoot.createApiInbox(department.inboxName());
                    log.info("Created Chatwoot inbox '{}' id={} for department {}", department.inboxName(), created, key);
                    return created;
                }));
    }

    /** «Входящие» and every department inbox (creating missing ones). */
    public List<Long> all() {
        List<Long> ids = new ArrayList<>();
        ids.add(intakeId());
        departments.departments().forEach(d -> ids.add(departmentInboxId(d)));
        return ids;
    }

    /** True for an inbox of the app (known already — resolved by {@link #all()} or a department lookup). */
    public boolean isAppInbox(long inboxId) {
        return inboxId == intakeId() || departmentInbox.containsValue(inboxId);
    }

    /** The public API key of the inbox (contact actions: seen, typing). */
    public String identifier(long inboxId) {
        return identifiers.computeIfAbsent(inboxId, id -> chatwoot.inboxSettings(id).identifier());
    }

    public String secret(long inboxId, boolean refresh) {
        if (refresh) {
            secrets.remove(inboxId);
        }
        return secrets.computeIfAbsent(inboxId, id -> chatwoot.inboxSettings(id).secret());
    }

    /** Points the inbox's webhook at {@code url} unless it already is. */
    public void ensureWebhook(long inboxId, String url) {
        ChatwootInboxSettings settings = chatwoot.inboxSettings(inboxId);
        if (settings.secret() != null) {
            secrets.put(inboxId, settings.secret());
        }
        if (!url.equals(settings.webhookUrl())) {
            chatwoot.setInboxWebhook(inboxId, url);
            log.info("Chatwoot inbox {} webhook set to {} (was {})", inboxId, url, settings.webhookUrl());
        }
    }
}
