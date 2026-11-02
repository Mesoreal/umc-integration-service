package ru.provless.integration.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.provless.integration.chatwoot.AppContactService;
import ru.provless.integration.chatwoot.ChatwootClient;
import ru.provless.integration.chatwoot.ChatwootConversation;

import java.util.Optional;
import java.util.UUID;

/**
 * The user's current Chatwoot conversation = the current request (plan-chat-connector.md §3). While it is open,
 * pending or snoozed, messages go there; once an agent resolved it, the user's next message starts a new one.
 * Only the outbound listener thread calls {@link #current}, so two conversations can't be created for one user at once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatConversations {

    private final ChatConversationRepository repository;
    private final ChatwootClient chatwoot;
    private final ChatInboxes inboxes;
    private final AppContactService appContacts;
    private final ChatwootPolls polls;
    private final ChatHeader header;

    /** The conversation to put the user's message into, created when there is none or the last one is resolved. */
    public ChatConversation current(UUID userId) {
        Optional<ChatConversation> known = repository.findByUserIdAndCurrentTrue(userId);
        if (known.isPresent()) {
            ChatConversation conversation = known.get();
            if (conversation.getTransferredTo() != null) {
                throw new TransferInProgressException(conversation.getConversationId());
            }
            Optional<ChatwootConversation> live = chatwoot.findConversation(conversation.getConversationId());
            if (live.isPresent() && !live.get().isResolved()) {
                refresh(conversation, live.get());
                return repository.save(conversation);
            }
            conversation.setCurrent(false);
            conversation.setStatus(live.map(ChatwootConversation::status).orElse("deleted"));
            repository.saveAndFlush(conversation);
        }

        long contactId = appContacts.ensure(userId, null);
        ChatwootConversation created = chatwoot.createConversation(inboxes.intakeId(), contactId, userId.toString());
        ChatConversation conversation = new ChatConversation(created.id(), userId, contactId, created.inboxId(),
                created.status());
        refresh(conversation, created);
        repository.save(conversation);
        log.info("Chat conversation {} started for userId={} (contact {})", created.id(), userId, contactId);
        // a new request has nobody on it yet: the app shows the personal manager or «Ваш менеджер», not the previous one
        header.send(userId, null, null, null);
        polls.sendHours(userId, created.inboxId());
        return conversation;
    }

    /** The current conversation if there is one; never creates (reads and typing don't open a request). */
    public Optional<ChatConversation> existing(UUID userId) {
        return repository.findByUserIdAndCurrentTrue(userId);
    }

    /** agent_last_seen_at is left to the read poll: taking it here would hide a read from it. */
    private static void refresh(ChatConversation conversation, ChatwootConversation live) {
        conversation.setStatus(live.status());
        conversation.setAssigneeId(live.assigneeId());
    }
}
