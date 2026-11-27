package ru.provless.integration.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.provless.integration.chatwoot.ChatwootClient;
import ru.provless.integration.order.OrderRouting;
import ru.provless.integration.chatwoot.ChatwootException;
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
    private final ChatInboxes inboxes;
    private final ChatStorage storage;
    private final ChatInboundPublisher inbound;
    private final OrderRouting orders;

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
            routeOrder(event);
            return;
        }
        ChatConversation conversation = conversations.current(event.userId());
        if (redelivered) {
            // created in Chatwoot, then the service died before it wrote the link: don't post it twice
            Optional<ChatwootClient.ChatwootMessageSummary> posted = chatwoot.recentMessages(conversation.getConversationId())
                    .stream().filter(m -> sourceId.equals(m.sourceId())).findFirst();
            if (posted.isPresent()) {
                confirm(save(posted.get().id(), posted.get().createdAt(), event, conversation));
                routeOrder(event);
                return;
            }
        }
        Long inReplyTo = quoted(event.replyToExternalId(), conversation.getConversationId());
        String text = event.order() == null ? event.text() : orderText(event.order());
        List<OutboundEvent.File> files = event.attachments() == null ? List.of() : event.attachments();
        // Chatwoot takes attachments up to 40 MB; bigger ones (contract §14.9a, up to 4 GB) go as a line with a link
        List<OutboundEvent.File> big = files.stream().filter(f -> f.size() > CHATWOOT_ATTACHMENT_MAX_BYTES).toList();
        List<ChatwootUpload> uploads = new ArrayList<>();
        for (OutboundEvent.File f : files) {
            if (f.size() <= CHATWOOT_ATTACHMENT_MAX_BYTES) {
                uploads.add(new ChatwootUpload(f.name(), f.mimeType(), storage.read(f.bucket(), f.objectKey())));
            }
        }
        ChatwootClient.ChatwootMessageRef created;
        try {
            created = chatwoot.createIncomingMessage(conversation.getConversationId(), withLinks(text, big), sourceId, inReplyTo,
                    uploads);
        } catch (ChatwootException e) {
            if (uploads.isEmpty() || e.getHttpStatus() < 400 || e.getHttpStatus() >= 500) {
                throw e;
            }
            // Chatwoot refused an attachment (type, size): the managers still get the message, every file as a link
            log.warn("Chatwoot refused the attachments of message {} ({}): sent as links", event.messageId(), e.getHttpStatus());
            created = chatwoot.createIncomingMessage(conversation.getConversationId(), withLinks(text, files), sourceId, inReplyTo,
                    List.of());
        }
        confirm(save(created.id(), created.createdAt(), event, conversation));
        routeOrder(event);
        log.info("Chat message {} of userId={} → Chatwoot {} (conversation {})", event.messageId(), event.userId(),
                created.id(), conversation.getConversationId());
    }

    /** Chatwoot's own limit of an attachment ({@code Attachment} validation, v4). */
    static final long CHATWOOT_ATTACHMENT_MAX_BYTES = 40L * 1024 * 1024;

    /** «📎 Договор.pdf · 1,2 ГБ: <link>» under the text, one line a file. */
    static String withLinks(String text, List<OutboundEvent.File> files) {
        if (files.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text == null || text.isBlank() ? "" : text + "\n\n");
        for (OutboundEvent.File f : files) {
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append("📎 ").append(f.name()).append(" · ").append(humanSize(f.size()));
            out.append(f.link() == null ? " — файл в чате приложения, ссылка не настроена" : ": " + f.link());
        }
        return out.toString();
    }

    /** 820 КБ, 45,3 МБ, 1,2 ГБ. */
    static String humanSize(long bytes) {
        String[] units = {"Б", "КБ", "МБ", "ГБ"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        String number = unit == 0 || value >= 100 ? String.valueOf(Math.round(value))
                : String.format(java.util.Locale.ROOT, "%.1f", value).replace('.', ',').replace(",0", "");
        return number + " " + units[unit];
    }

    /** A placed order: to sales, to the personal manager, with the order's note (once; a repeat finds it done). */
    private void routeOrder(OutboundEvent event) {
        if (event.order() != null) {
            orders.placed(event.userId(), event.order());
        }
    }

    /** What the agent sees as the user's message: «🛒 Заказ № 1001 · 2 поз. · 6 590,00 ₽». */
    static String orderText(OutboundEvent.Order order) {
        return "🛒 Заказ № " + order.number() + " · " + order.positionsCount() + " поз. · "
                + (order.total() == null ? "" : OrderRouting.money(order.total().amount()) + " ₽");
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

    /**
     * A quote works only inside one Chatwoot conversation; a quote of an earlier request is dropped. chat-service knows
     * the message by its first Chatwoot id; after a transfer it lives on as a copy — the link knows where.
     */
    private Long quoted(String externalId, long conversationId) {
        Long cwId = ChatMessageLink.cwId(externalId);
        if (cwId == null) {
            return null;
        }
        return links.findByExternalCwId(cwId).filter(l -> l.getConversationId() == conversationId)
                .map(ChatMessageLink::getCwMessageId).orElse(null);
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
                chatwoot.markSeenByContact(inboxes.identifier(c.getInboxId()), event.userId().toString(), c.getConversationId()));
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
        conversations.existing(event.userId()).ifPresent(c -> chatwoot.setContactTyping(inboxes.identifier(c.getInboxId()),
                event.userId().toString(), c.getConversationId(), Boolean.TRUE.equals(event.isTyping())));
    }
}
