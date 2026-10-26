package ru.provless.integration.chatwoot;

import java.util.Map;

/** The subset of a Chatwoot contact this service reads. */
public record ChatwootContact(long id, String name, String phoneNumber, String identifier,
                              Map<String, Object> customAttributes) {
}
