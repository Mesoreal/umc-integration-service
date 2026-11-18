package ru.provless.integration.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.provless.integration.chatwoot.ChatwootClient;

/**
 * The Chatwoot inbox «Проверка документов» (front requests S5 §10): found by name or created once, an API inbox without a webhook —
 * nothing written there reaches the client. Who works in it (sales) is set in Chatwoot.
 */
@Slf4j
@Component
public class ReviewInbox {

    private final ChatwootClient chatwoot;
    private final String name;
    private volatile Long id;

    public ReviewInbox(ChatwootClient chatwoot, @Value("${review.inbox-name:Проверка документов}") String name) {
        this.chatwoot = chatwoot;
        this.name = name;
    }

    public long id() {
        Long known = id;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (id == null) {
                id = chatwoot.findInboxIdByName(name).orElseGet(() -> {
                    long created = chatwoot.createApiInbox(name);
                    log.info("Created Chatwoot inbox '{}' id={}", name, created);
                    return created;
                });
            }
            return id;
        }
    }
}
