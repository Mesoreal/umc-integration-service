package ru.provless.integration.chatwoot;

/** An agent who works an inbox; {@code thumbnail} — the avatar's URL, null without one. */
public record ChatwootInboxMember(long id, String name, String email, String thumbnail) {
}
