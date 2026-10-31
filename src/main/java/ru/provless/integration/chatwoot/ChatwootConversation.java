package ru.provless.integration.chatwoot;

import java.time.Instant;

/**
 * A conversation as the application API returns it. {@code id} is Chatwoot's display_id (the id of its API paths);
 * {@code assigneeId} null — nobody assigned; {@code agentLastSeenAt} null — no agent opened it yet. {@code sourceId}
 * (the contact's id in the inbox) is only in webhook payloads; the REST view leaves it out (null).
 */
public record ChatwootConversation(long id, long inboxId, String status, Long assigneeId, String assigneeName,
                                   Instant agentLastSeenAt, String sourceId) {

    public boolean isResolved() {
        return "resolved".equals(status);
    }
}
