package ru.provless.integration.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.provless.integration.chatwoot.AppContactLink;
import ru.provless.integration.chatwoot.AppContactLinkRepository;

import java.util.UUID;

/**
 * Links people open in Chatwoot: the client's contact page — their chat and orders; a conversation's address changes when it moves
 * to a department, the contact's does not. No {@code chatwoot.public-url} — no links.
 */
@Component
public class ChatwootLinks {

    private final AppContactLinkRepository contacts;
    private final String url;
    private final String accountId;

    public ChatwootLinks(AppContactLinkRepository contacts, @Value("${chatwoot.public-url:}") String url,
                         @Value("${chatwoot.account-id:}") String accountId) {
        this.contacts = contacts;
        this.url = url.replaceAll("/+$", "");
        this.accountId = accountId;
    }

    public String contact(String userId) {
        if (url.isEmpty() || userId == null) {
            return null;
        }
        return contacts.findById(UUID.fromString(userId)).map(AppContactLink::getContactId)
                .map(contact -> url + "/app/accounts/" + accountId + "/contacts/" + contact).orElse(null);
    }
}
