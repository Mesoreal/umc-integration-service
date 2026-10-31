package ru.provless.integration.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.provless.integration.chatwoot.ChatwootAppInbox;
import ru.provless.integration.chatwoot.ChatwootClient;
import ru.provless.integration.chatwoot.ChatwootUpload;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies one {@code chat.outbound} event to Chatwoot (plan-chat-connector.md §2.1). Every step is safe to repeat:
 * the outbox of chat-service may deliver an event twice, and a crash may come between a Chatwoot call and our records.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatOutboundHandler {

    static final String EDITED_NOTE = "✏️ Клиент изменил сообщение:\n";
    static final String DELETED_NOTE = "❌ Клиент удалил это сообщение";

    private final ChatConversations conversations;
    private final ChatMessageLinkRepository links;
    private final ChatwootClient chatwoot;
    private final ChatwootAppInbox appInbox;
    private final ChatStorage storage;
    private final ChatInboundPublisher inbound;

    /** @param redelivered the broker gave this event before: the message may already be in Chatwoot */
    public void handle(OutboundEvent event, boolean redelivered) {
        if (event.userId() == null || event.type() == null) {
            throw new IllegalArgumentException("chat.outbound event without type or user_id");
        }
        switch (event.type()) {
            case "message.created" -> messageCreated(event, redelivered);
            case "message.edited" -> note(event, EDITED_NOTE + (event.text() == null ? "" : event.text()));
            case "message.deleted" -> note(event, DELETED_NOTE);
            case "client.read" -> read(event);
            case "client.delivered" -> delivered(event);
            case "client.typing" -> typing(event);
            default -> log.warn("Unknown chat.outbound type skipped: {}", event.type());
        }
    }

    private void messageCreated(OutboundEvent event, boolean redelivered) {
        if (event.messageId() == null) {
            throw new IllegalArgumentException("message.created without message_id");
        }
        String sourceId = "app:" + event.messageId();
        Optional<ChatMessageLink> known = links.findByMessageId(event.messageId());
        if (known.isPresent()) {
            confirm(known.get());
            return;
        }
        ChatConversation conversation = conversations.current(event.userId());
        if (redelivered) {
            // created in Chatwoot, then the service died before it wrote the link: don't post it twice
            Optional<ChatwootClient.ChatwootMessageSummary> posted = chatwoot.recentMessages(conversation.getConversationId())
                    .stream().filter(m -> sourceId.equals(m.sourceId())).findFirst();
            if (posted.isPresent()) {
                confirm(save(posted.get().id(), posted.get().createdAt(), event, conversation));
                return;
            }
        }
        Long inReplyTo = quoted(event.replyToExternalId(), conversation.getConversationId());
        List<ChatwootUpload> uploads = new ArrayList<>();
        for (OutboundEvent.File f : event.attachments() == null ? List.<OutboundEvent.File>of() : event.attachments()) {
            uploads.add(new ChatwootUpload(f.name(), f.mimeType(), storage.read(f.bucket(), f.objectKey())));
        }
        ChatwootClient.ChatwootMessageRef created = chatwoot.createIncomingMessage(conversation.getConversationId(),
                event.text(), sourceId, inReplyTo, uploads);
        confirm(save(created.id(), created.createdAt(), event, conversation));
        log.info("Chat message {} of userId={} → Chatwoot {} (conversation {})", event.messageId(), event.userId(),
                created.id(), conversation.getConversationId());
    }

    private ChatMessageLink save(long cwId, Instant cwCreatedAt, OutboundEvent event, ChatConversation conversation) {
        Instant at = cwCreatedAt != null ? cwCreatedAt : Instant.now();
        return links.save(new ChatMessageLink(cwId, event.messageId(), event.userId(), conversation.getConversationId(),
                ChatMessageLink.CLIENT, at.atOffset(ZoneOffset.UTC)));
    }

    /** The message is on the managers' side: ✓✓ for the user. */
    private void confirm(ChatMessageLink link) {
        inbound.send(new InboundEvents.ClientMessageDelivered(link.getUserId(), link.getMessageId(), link.externalId()));
    }

    /** A quote works only inside one Chatwoot conversation; a quote of an earlier request is dropped. */
    private Long quoted(String externalId, long conversationId) {
        Long cwId = ChatMessageLink.cwId(externalId);
        if (cwId == null) {
            return null;
        }
        return links.findById(cwId).filter(l -> l.getConversationId() == conversationId).map(ChatMessageLink::getCwMessageId)
                .orElse(null);
    }

    /** Edits and deletions: a private note under the original (no editing through the API — decision 31.10.2026). */
    private void note(OutboundEvent event, String text) {
        Optional<ChatMessageLink> original = event.messageId() == null ? Optional.empty() : links.findByMessageId(event.messageId());
        if (original.isEmpty()) {
            log.warn("{} for message {} that never reached Chatwoot — skipped", event.type(), event.messageId());
            return;
        }
        chatwoot.createPrivateNote(original.get().getConversationId(), text, original.get().getCwMessageId());
    }

    /**
     * Chatwoot marks every agent message up to now read (public update_last_seen). Read covers delivered: those links
     * are only flagged, no separate «delivered» call (Chatwoot would ignore a step back anyway).
     */
    private void read(OutboundEvent event) {
        conversations.existing(event.userId()).ifPresent(c ->
                chatwoot.markSeenByContact(appInbox.identifier(), event.userId().toString(), c.getConversationId()));
        markDelivered(event, false);
    }

    private void delivered(OutboundEvent event) {
        markDelivered(event, true);
    }

    private void markDelivered(OutboundEvent event, boolean tellChatwoot) {
        Long upTo = ChatMessageLink.cwId(event.upToExternalId());
        if (upTo == null) {
            return;
        }
        for (ChatMessageLink link : links.undeliveredUpTo(event.userId(), upTo)) {
            if (tellChatwoot) {
                chatwoot.updateMessageStatus(link.getConversationId(), link.getCwMessageId(), "delivered");
            }
            link.setDeliveredToClient(true);
            links.save(link);
        }
    }

    private void typing(OutboundEvent event) {
        conversations.existing(event.userId()).ifPresent(c -> chatwoot.setContactTyping(appInbox.identifier(),
                event.userId().toString(), c.getConversationId(), Boolean.TRUE.equals(event.isTyping())));
    }
}
