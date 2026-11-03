package ru.provless.integration.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.provless.integration.chatwoot.ChatwootClient;
import ru.provless.integration.chatwoot.ChatwootConversation;
import ru.provless.integration.chatwoot.ChatwootMessage;
import ru.provless.integration.chatwoot.ChatwootUpload;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Moves the user's request to a department's inbox (plan-chat-departments.md §2). A Chatwoot conversation can't change
 * its inbox, so the connector makes a new one there, copies the messages (variant A of Q-D1: the original time in the
 * text — the API can't set it), makes it the current request and deletes the old one, which the previous inbox must
 * not keep seeing. Each step checks what is done already: a transfer that broke halfway goes on from there.
 */
@Slf4j
@Service
public class ChatTransfers {

    private final ChatwootClient chatwoot;
    private final ChatInboxes inboxes;
    private final ChatConversationRepository conversations;
    private final ChatMessageLinkRepository links;
    private final ChatTransferCopyRepository copies;
    private final ChatwootFiles files;
    private final ChatwootPolls polls;
    private final ChatHeader header;
    private final DateTimeFormatter stamp;

    public ChatTransfers(ChatwootClient chatwoot, ChatInboxes inboxes, ChatConversationRepository conversations,
                         ChatMessageLinkRepository links, ChatTransferCopyRepository copies, ChatwootFiles files,
                         ChatwootPolls polls, ChatHeader header,
                         @Value("${chatwoot.chat.copy-timezone:Europe/Moscow}") String timezone) {
        this.chatwoot = chatwoot;
        this.inboxes = inboxes;
        this.conversations = conversations;
        this.links = links;
        this.copies = copies;
        this.files = files;
        this.polls = polls;
        this.header = header;
        this.stamp = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of(timezone));
    }

    /**
     * The mark on a conversation a transfer creates. Its webhook may come before the transfer records the conversation (a transfer
     * of an order runs on another thread than the webhooks): the webhook leaves such a conversation to the transfer.
     */
    static final String TRANSFER_FROM = "transfer_from";

    public void transfer(ChatConversation from, ChatDepartments.Department department) {
        long target = inboxes.departmentInboxId(department);
        if (from.getInboxId() == target || isDone(from)) {
            return;
        }
        ChatConversation to = start(from, target);
        copyMessages(from, to);
        finish(from, to);
    }

    /** Moved already: the old conversation points at the new one and is no longer the request. */
    static boolean isDone(ChatConversation conversation) {
        return conversation.getTransferredTo() != null && !conversation.isCurrent();
    }

    /** The new conversation in the department, remembered on the old one: from now the user's messages wait. */
    private ChatConversation start(ChatConversation from, long target) {
        if (from.getTransferredTo() != null) {
            return conversations.findById(from.getTransferredTo()).orElseThrow();
        }
        String userId = from.getUserId().toString();
        chatwoot.addContactInbox(from.getContactId(), target, userId);
        ChatwootConversation created = chatwoot.createConversation(target, from.getContactId(), userId,
                Map.of(TRANSFER_FROM, from.getConversationId()));
        chatwoot.createPrivateNote(created.id(), "↪ Передано из «" + inboxes.name(from.getInboxId()) + "» "
                + stamp.format(Instant.now()) + ". Ниже — переписка обращения, время сообщений — в их первой строке.", null);
        ChatConversation to = new ChatConversation(created.id(), from.getUserId(), from.getContactId(), target, created.status());
        to.setCurrent(false);
        to.setAssigneeId(created.assigneeId());
        conversations.save(to);
        from.setTransferredTo(created.id());
        conversations.save(from);
        log.info("Transfer of conversation {} (userId={}) to inbox {} started: conversation {}",
                from.getConversationId(), from.getUserId(), target, created.id());
        return to;
    }

    /** Messages people wrote (not Chatwoot's own lines, not deleted), oldest first, each once. */
    private void copyMessages(ChatConversation from, ChatConversation to) {
        for (ChatwootMessage m : chatwoot.allMessages(from.getConversationId())) {
            if (m.type() != ChatwootMessage.INCOMING && m.type() != ChatwootMessage.OUTGOING || m.deleted()
                    || copies.existsById(m.id())) {
                continue;
            }
            if ((m.content() == null || m.content().isBlank()) && m.attachments().isEmpty()) {
                continue;
            }
            List<ChatwootUpload> uploads = new ArrayList<>();
            for (String url : m.attachments()) {
                ChatwootFiles.Fetched file = files.fetch(url);
                uploads.add(new ChatwootUpload(file.name(), file.mimeType(), file.content()));
            }
            Long inReplyTo = m.inReplyTo() == null ? null
                    : copies.findById(m.inReplyTo()).map(ChatTransferCopy::getNewCwMessageId).orElse(null);
            ChatwootClient.ChatwootMessageRef copy = chatwoot.createCopy(to.getConversationId(), m, copyText(m),
                    "copy:" + m.id(), inReplyTo, uploads);
            copies.save(new ChatTransferCopy(m.id(), copy.id(), to.getConversationId()));
            Instant at = copy.createdAt() != null ? copy.createdAt() : Instant.now();
            links.repoint(m.id(), copy.id(), to.getConversationId(), at.atOffset(ZoneOffset.UTC));
        }
    }

    /** «🕓 01.11 15:22» first; whose words, when it is not the user's: the copies are posted by the service user. */
    String copyText(ChatwootMessage m) {
        StringBuilder head = new StringBuilder("🕓 ").append(stamp.format(m.createdAt() != null ? m.createdAt() : Instant.now()));
        if (m.isPrivate()) {
            head.append(" · заметка");
        }
        if (m.type() == ChatwootMessage.OUTGOING && m.senderName() != null) {
            head.append(" · ").append(m.senderName());
        }
        return m.content() == null || m.content().isBlank() ? head.toString() : head + "\n" + m.content();
    }

    /**
     * The old conversation goes first (the previous inbox must not keep it; a 404 of a repeat is fine), then the new one
     * becomes the request — in that order a failure in between is retried with the delete again.
     */
    private void finish(ChatConversation from, ChatConversation to) {
        chatwoot.deleteConversation(from.getConversationId());
        from.setCurrent(false);
        from.setStatus("transferred");
        conversations.saveAndFlush(from);
        to.setCurrent(true);
        conversations.save(to);

        Optional<ChatwootConversation> live = chatwoot.findConversation(to.getConversationId());
        Long assignee = live.map(ChatwootConversation::assigneeId).orElse(null);
        to.setAssigneeId(assignee);
        conversations.save(to);
        header.send(to.getUserId(), assignee, live.map(ChatwootConversation::assigneeName).orElse(null), null);
        polls.sendHours(to.getUserId(), to.getInboxId());
        log.info("Conversation {} of userId={} moved to {} as conversation {}", from.getConversationId(), from.getUserId(),
                inboxes.name(to.getInboxId()), to.getConversationId());
    }
}
