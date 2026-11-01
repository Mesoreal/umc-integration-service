package ru.provless.integration.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.provless.integration.chat.ChatInboxes;
import ru.provless.integration.chatwoot.ChatwootClient;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Account deleted (BE-15). Chatwoot first (owner's decision 1 of account-deletion/plan.md): the app's conversations of
 * the contact go; the contact goes too when the app was its only channel, otherwise — the person also writes in a
 * messenger — it stays without {@code app_user_id}. Then this service's records: the contact link, the chat connector's
 * tables, the UMC document reviews (their files are profile-service's, deleted there). amoCRM deals stay (decision 2).
 * Chatwoot before the records: a retry still knows the contact. Twice is harmless.
 */
@Slf4j
@Component
public class AccountDeletedListener {

    static final int MAX_ROUNDS = 20;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ChatwootClient chatwoot;
    private final ChatInboxes inboxes;
    private final boolean chatwootEnabled;
    private final ObjectMapper json = new ObjectMapper();

    public AccountDeletedListener(JdbcTemplate jdbc, TransactionTemplate tx, ChatwootClient chatwoot, ChatInboxes inboxes,
                                  @Value("${chatwoot.enabled:false}") boolean chatwootEnabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.chatwoot = chatwoot;
        this.inboxes = inboxes;
        this.chatwootEnabled = chatwootEnabled;
    }

    @RabbitListener(queues = AccountDeletedQueues.QUEUE)
    public void onDeleted(Message message) {
        UUID userId;
        try {
            userId = UUID.fromString(json.readTree(message.getBody()).path("user_id").asText());
        } catch (IOException | IllegalArgumentException e) {
            throw new AmqpRejectAndDontRequeueException("Unreadable account.deleted event", e);
        }
        if (chatwootEnabled) {
            contactOf(userId).ifPresent(contact -> cleanChatwoot(userId, contact));
        }
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM chatwoot.chat_message_link WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM chatwoot.chat_transfer_copy WHERE new_conversation_id IN "
                    + "(SELECT conversation_id FROM chatwoot.chat_conversation WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM chatwoot.chat_conversation WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM chatwoot.app_contact_link WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM umc.document_review WHERE user_id = ?", userId);
        });
        log.info("Account {} deleted", userId);
    }

    private java.util.Optional<Long> contactOf(UUID userId) {
        List<Long> ids = jdbc.queryForList("SELECT contact_id FROM chatwoot.app_contact_link WHERE user_id = ? "
                + "UNION SELECT contact_id FROM chatwoot.chat_conversation WHERE user_id = ?", Long.class, userId, userId);
        return ids.stream().findFirst();
    }

    private void cleanChatwoot(UUID userId, long contact) {
        Set<Long> app = Set.copyOf(inboxes.all());
        // Chatwoot deletes a conversation in the background: it may still be listed right after; the list is a page of 25
        Set<Long> deleted = new java.util.HashSet<>();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<long[]> ofApp = chatwoot.contactConversations(contact).stream()
                    .filter(c -> app.contains(c[1]) && !deleted.contains(c[0])).toList();
            if (ofApp.isEmpty()) {
                break;
            }
            for (long[] conversation : ofApp) {
                chatwoot.deleteConversation(conversation[0]);
                deleted.add(conversation[0]);
            }
        }
        Set<Long> channels = chatwoot.contactInboxIds(contact).stream().collect(Collectors.toSet());
        boolean onlyApp = app.containsAll(channels);
        if (onlyApp) {
            chatwoot.deleteContact(contact);
        } else {
            chatwoot.removeContactAttributes(contact, List.of("app_user_id"));
        }
        log.info("Chatwoot of userId={}: contact {} {}, app conversations deleted={}", userId, contact,
                onlyApp ? "deleted" : "kept (other channels)", deleted.size());
    }
}
