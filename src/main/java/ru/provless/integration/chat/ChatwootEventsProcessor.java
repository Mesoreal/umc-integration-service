package ru.provless.integration.chat;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What agents do in the app inbox, from its webhooks → {@code chat.inbound} (plan-chat-connector.md §2.2). The user is
 * the {@code source_id} of the conversation's contact_inbox — the connector set it to the user id. Everything is safe to
 * repeat: a message already forwarded is recognised by its Chatwoot id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatwootEventsProcessor {

    private final ChatConversationRepository conversations;
    private final ChatMessageLinkRepository links;
    private final ChatwootFiles files;
    private final ChatStorage storage;
    private final ChatInboundPublisher inbound;
    private final ChatwootPolls polls;
    private final ChatDepartments departments;
    private final ChatInboxes inboxes;
    private final ChatTransfers transfers;

    public void process(JsonNode event) {
        String type = event.path("event").asText("");
        switch (type) {
            case "message_created" -> messageCreated(event);
            case "message_updated" -> messageUpdated(event);
            case "conversation_typing_on", "conversation_typing_off" -> typing(event, type.endsWith("_on"));
            case "conversation_created", "conversation_updated", "conversation_status_changed" -> conversationChanged(event);
            default -> log.debug("Chatwoot app event ignored: {}", type);
        }
    }

    // ─── messages ───────────────────────────────────────────────────────────

    /**
     * Only what an agent wrote to the user: outgoing, not a note, sent by a user (not a bot, not an automation), and
     * typed in Chatwoot — no {@code source_id}. Messages the connector posts itself (copies made by a transfer to a
     * department) carry one and are not sent to the app again.
     */
    private void messageCreated(JsonNode m) {
        if (!"outgoing".equals(m.path("message_type").asText()) || m.path("private").asBoolean(false)
                || !"user".equals(m.path("sender").path("type").asText())
                || !m.path("source_id").asText("").isEmpty()) {
            return;
        }
        long cwId = m.path("id").asLong();
        if (links.existsById(cwId)) {
            return;
        }
        JsonNode conversation = m.path("conversation");
        Optional<UUID> userId = userOf(conversation);
        if (userId.isEmpty()) {
            return;
        }
        long conversationId = conversation.path("id").asLong();
        track(conversationId, userId.get(), conversation);

        List<InboundEvents.File> attachments = new ArrayList<>();
        for (JsonNode a : m.path("attachments")) {
            ChatwootFiles.Fetched file = files.fetch(a.path("data_url").asText());
            String key = storage.storeForUser(userId.get(), file.mimeType(), file.content());
            Integer width = a.hasNonNull("width") ? a.path("width").asInt() : null;
            Integer height = a.hasNonNull("height") ? a.path("height").asInt() : null;
            attachments.add(new InboundEvents.File(file.name(), file.mimeType(), file.content().length, key, width, height));
        }
        JsonNode inReplyTo = m.path("content_attributes").path("in_reply_to");
        String replyTo = inReplyTo.canConvertToLong() && inReplyTo.asLong() > 0
                ? ChatMessageLink.externalId(inReplyTo.asLong()) : null;
        JsonNode sender = m.path("sender");
        String content = m.path("content").isTextual() ? m.path("content").asText() : null;

        inbound.send(new InboundEvents.ManagerMessage(userId.get(), ChatMessageLink.externalId(cwId),
                new InboundEvents.Sender(InboundEvents.managerId(sender.path("id").asLong()), senderName(sender, conversation), null),
                content, attachments, replyTo));
        links.save(new ChatMessageLink(cwId, null, userId.get(), conversationId, ChatMessageLink.MANAGER,
                createdAt(m).atOffset(ZoneOffset.UTC)));
        log.info("Chatwoot message {} of conversation {} → userId={}", cwId, conversationId, userId.get());
    }

    /** An agent deleted a message the user had: it disappears in the app too. */
    private void messageUpdated(JsonNode m) {
        if (!m.path("content_attributes").path("deleted").asBoolean(false)) {
            return;
        }
        long cwId = m.path("id").asLong();
        links.findById(cwId)
                .filter(l -> ChatMessageLink.MANAGER.equals(l.getDirection()))
                .ifPresent(l -> inbound.send(new InboundEvents.ManagerMessageDeleted(l.getUserId(), l.externalId())));
    }

    private void typing(JsonNode event, boolean on) {
        if (event.path("is_private").asBoolean(false) || !"user".equals(event.path("user").path("type").asText())) {
            return;
        }
        userOf(event.path("conversation")).ifPresent(userId ->
                inbound.send(new InboundEvents.ManagerTyping(userId, on, event.path("user").path("name").asText(null))));
    }

    // ─── conversations ──────────────────────────────────────────────────────

    /**
     * Status and assignee of a conversation. For the user's current one a new assignee is the new manager in the app's
     * header; a resolved one stays current until the user writes again (then a new request starts).
     */
    void conversationChanged(JsonNode conversation) {
        Optional<UUID> userId = userOf(conversation);
        if (userId.isEmpty()) {
            return;
        }
        long conversationId = conversation.path("id").asLong();
        ChatConversation known = track(conversationId, userId.get(), conversation);
        if (ChatTransfers.isDone(known)) {
            // a late event of a conversation that was moved (and deleted): nothing in it matters any more
            return;
        }
        String status = conversation.path("status").asText(known.getStatus());
        known.setStatus(status);

        JsonNode assignee = conversation.path("meta").path("assignee");
        Long assigneeId = assignee.isObject() && assignee.hasNonNull("id") ? assignee.path("id").asLong() : null;
        boolean changed = !Objects.equals(assigneeId, known.getAssigneeId());
        known.setAssigneeId(assigneeId);
        conversations.save(known);
        if (changed && known.isCurrent()) {
            inbound.send(new InboundEvents.ManagerChanged(userId.get(), assigneeId == null ? null : new InboundEvents.Manager(
                    InboundEvents.managerId(assigneeId), name(assignee), null, false,
                    presence(assignee.path("availability_status").asText("offline")), null)));
        }
        transferIfLabelled(known, conversation);
    }

    /**
     * A department's label on the user's current request (or one whose transfer broke halfway) moves it to that
     * department's inbox; the label of the department the request is already in means nothing.
     */
    private void transferIfLabelled(ChatConversation known, JsonNode conversation) {
        if (!known.isCurrent() && known.getTransferredTo() == null) {
            return;
        }
        for (JsonNode label : conversation.path("labels")) {
            Optional<ChatDepartments.Department> department = departments.byLabel(label.asText(""));
            if (department.isPresent() && inboxes.departmentInboxId(department.get()) != known.getInboxId()) {
                transfers.transfer(known, department.get());
                return;
            }
        }
    }

    /**
     * The conversation as the connector knows it. One an agent started (the user hasn't written there) becomes the
     * user's current one: agents talk to the user there now.
     */
    private ChatConversation track(long conversationId, UUID userId, JsonNode conversation) {
        Optional<ChatConversation> known = conversations.findById(conversationId);
        if (known.isPresent()) {
            return known.get();
        }
        conversations.findByUserIdAndCurrentTrue(userId).ifPresent(previous -> {
            previous.setCurrent(false);
            conversations.saveAndFlush(previous);
        });
        ChatConversation created = new ChatConversation(conversationId, userId,
                conversation.path("meta").path("sender").path("id").asLong(), conversation.path("inbox_id").asLong(),
                conversation.path("status").asText("open"));
        conversations.save(created);
        polls.sendHours(userId, created.getInboxId());
        return created;
    }

    /** online/busy/offline of Chatwoot → online/away/offline of the app contract. */
    static String presence(String availability) {
        return switch (availability) {
            case "online" -> "online";
            case "busy" -> "away";
            default -> "offline";
        };
    }

    private static Optional<UUID> userOf(JsonNode conversation) {
        String sourceId = conversation.path("contact_inbox").path("source_id").asText("");
        try {
            return Optional.of(UUID.fromString(sourceId));
        } catch (IllegalArgumentException e) {
            log.debug("Chatwoot conversation {} is not an app user's (source_id '{}')", conversation.path("id").asLong(), sourceId);
            return Optional.empty();
        }
    }

    /** The assignee's display name if the sender is the assignee, else the sender's name. */
    private static String senderName(JsonNode sender, JsonNode conversation) {
        JsonNode assignee = conversation.path("meta").path("assignee");
        if (assignee.isObject() && assignee.path("id").asLong() == sender.path("id").asLong()) {
            return name(assignee);
        }
        return sender.path("name").asText(null);
    }

    private static String name(JsonNode agent) {
        String available = agent.path("available_name").asText("");
        return available.isBlank() ? agent.path("name").asText(null) : available;
    }

    private static Instant createdAt(JsonNode m) {
        JsonNode at = m.path("created_at");
        if (at.isNumber()) {
            return Instant.ofEpochSecond(at.asLong());
        }
        try {
            return Instant.parse(at.asText());
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }
}
