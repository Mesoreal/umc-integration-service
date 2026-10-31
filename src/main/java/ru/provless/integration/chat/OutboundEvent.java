package ru.provless.integration.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An event of {@code chat.outbound} (formats: plan-chat-service.md §5). One flat shape for every {@code type}; each type
 * fills its own fields. Unknown fields are ignored, so chat-service can add some without breaking the connector.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OutboundEvent(String type, UUID eventId, UUID userId,
                            // message.created / edited / deleted
                            UUID messageId, Long seq, String text, List<File> attachments, UUID replyToId,
                            String replyToExternalId, String externalId, String previousText,
                            Instant createdAt, Instant editedAt, Instant deletedAt,
                            // client.read / client.delivered
                            Long upToSeq, String upToExternalId, Instant at,
                            // client.typing
                            Boolean isTyping) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record File(UUID fileId, String name, String mimeType, long size, String bucket, String objectKey) {
    }
}
