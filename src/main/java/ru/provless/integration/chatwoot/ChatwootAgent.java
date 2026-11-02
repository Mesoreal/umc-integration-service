package ru.provless.integration.chatwoot;

/**
 * An agent of the account; {@code availability} is online, busy or offline (presence-aware with auto_offline);
 * {@code avatarUrl} null without a photo.
 */
public record ChatwootAgent(long id, String name, String availability, String avatarUrl) {
}
