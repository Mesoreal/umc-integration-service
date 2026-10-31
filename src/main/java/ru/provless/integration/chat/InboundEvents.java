package ru.provless.integration.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the connector sends to {@code chat.inbound} (formats: plan-chat-service.md §5). */
public final class InboundEvents {

    private InboundEvents() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ClientMessageDelivered(String type, UUID userId, UUID messageId, String externalId) {
        public ClientMessageDelivered(UUID userId, UUID messageId, String externalId) {
            this("client.message_delivered", userId, messageId, externalId);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ManagerMessage(String type, UUID eventId, UUID userId, String externalId, String author, Sender sender,
                                 String text, List<File> attachments, String replyToExternalId) {
        public ManagerMessage(UUID userId, String externalId, Sender sender, String text, List<File> attachments,
                              String replyToExternalId) {
            this("manager.message", UUID.randomUUID(), userId, externalId, "manager", sender, text, attachments,
                    replyToExternalId);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Sender(String id, String name, String avatarUrl) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record File(String name, String mimeType, long size, String objectKey, Integer width, Integer height) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ManagerMessageDeleted(String type, UUID userId, String externalId) {
        public ManagerMessageDeleted(UUID userId, String externalId) {
            this("manager.message_deleted", userId, externalId);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ManagerRead(String type, UUID userId, Instant readAt) {
        public ManagerRead(UUID userId, Instant readAt) {
            this("manager.read", userId, readAt);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ManagerTyping(String type, UUID userId, boolean isTyping, String name) {
        public ManagerTyping(UUID userId, boolean isTyping, String name) {
            this("manager.typing", userId, isTyping, name);
        }
    }

    /** {@code manager} null — nobody handles the user's request yet. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ManagerChanged(String type, UUID userId, Manager manager) {
        public ManagerChanged(UUID userId, Manager manager) {
            this("manager.changed", userId, manager);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Manager(String id, String name, String avatarUrl, boolean isPersonal, String status, Instant lastSeenAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ManagerStatus(String type, String managerId, String status, Instant lastSeenAt) {
        public ManagerStatus(String managerId, String status, Instant lastSeenAt) {
            this("manager.status", managerId, status, lastSeenAt);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorkingHoursChanged(String type, UUID userId, String timezone, List<Day> days) {
        public WorkingHoursChanged(UUID userId, String timezone, List<Day> days) {
            this("working_hours.changed", userId, timezone, days);
        }
    }

    /** {@code weekday} 1 = Monday … 7 = Sunday; {@code open}/{@code close} {@code HH:mm}, null = day off. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Day(int weekday, String open, String close) {
    }

    /** How chat-service knows a Chatwoot agent: {@code cw:<id>}. */
    public static String managerId(long agentId) {
        return "cw:" + agentId;
    }
}
