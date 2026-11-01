package ru.provless.integration.chatwoot;

import java.time.Instant;
import java.util.List;

/**
 * A message of a conversation as the application API lists it. {@code type}: 0 incoming, 1 outgoing, 2 activity
 * (Chatwoot's own lines: «resolved», «assigned to…»), 3 template. {@code attachments} — their {@code data_url}s.
 */
public record ChatwootMessage(long id, int type, boolean isPrivate, String content, Instant createdAt, String sourceId,
                              String senderName, String senderType, Long inReplyTo, boolean deleted,
                              List<String> attachments) {

    public static final int INCOMING = 0;
    public static final int OUTGOING = 1;
    public static final int ACTIVITY = 2;
}
