package ru.provless.integration.chatwoot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.provless.integration.profile.ContactInfo;
import ru.provless.integration.profile.ProfileServiceClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Attaches an app user to the Chatwoot contact of the same person.
 *
 * One phone = one person = one contact: Chatwoot keeps phone numbers unique per account and the
 * messenger bridges already reuse the contact with the same phone. So the app does not create a
 * second contact — it finds the one with the confirmed phone and adds itself as one more channel
 * (contact_inbox in «Приложение», source_id = userId), or creates the contact when nobody has
 * written from this number yet. Idempotent: called on registration, on profile name changes and
 * lazily (BE-19 chat open). Plan: docs/backend/artifacts/shop/chatwoot/plan-app-contact-merge.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppContactService {

    static final String APP_USER_ID_ATTRIBUTE = "app_user_id";

    /** Names bridges give a contact when the messenger did not tell them a real one. */
    private static final Pattern BRIDGE_PLACEHOLDER_NAME = Pattern.compile("^(Telegram \\d+|VK id\\d+|MAX -?\\d+|\\+?\\d{7,15})$");

    private final ChatwootClient chatwootClient;
    private final ChatwootAppInbox appInbox;
    private final AppContactLinkRepository linkRepository;
    private final ProfileServiceClient profileServiceClient;

    @Value("${chatwoot.enabled:false}")
    private boolean enabled;

    private final ConcurrentHashMap<UUID, Object> userLocks = new ConcurrentHashMap<>();

    /**
     * @param phone the phone auth-service confirmed by OTP; null = take it from the profile
     * @return the Chatwoot contact id
     */
    public long ensure(UUID userId, String phone) {
        if (!enabled) {
            throw new ChatwootDisabledException();
        }
        // Registration and profile-update events for one user can arrive together on two listener threads.
        synchronized (userLocks.computeIfAbsent(userId, id -> new Object())) {
            Optional<ContactInfo> profile = profileServiceClient.findContactInfo(userId);
            String fullName = profile.map(ContactInfo::fullName).filter(n -> !n.isBlank()).orElse(null);
            String confirmedPhone = normalizePhone(phone != null ? phone : profile.map(ContactInfo::phone).orElse(null));
            if (confirmedPhone == null) {
                throw new IllegalStateException("No valid phone for userId=" + userId + " — cannot match a Chatwoot contact");
            }
            return ensureLocked(userId, confirmedPhone, fullName);
        }
    }

    private long ensureLocked(UUID userId, String phone, String fullName) {
        Optional<AppContactLink> link = linkRepository.findById(userId);
        if (link.isPresent()) {
            Optional<ChatwootContact> linked = chatwootClient.findContact(link.get().getContactId());
            if (linked.isPresent()) {
                syncName(linked.get(), fullName);
                return linked.get().id();
            }
            // Merged into another contact or deleted by an agent — find where the person is now.
            log.info("Chatwoot contact {} of userId={} is gone, re-resolving", link.get().getContactId(), userId);
            linkRepository.delete(link.get());
        }

        long inboxId = appInbox.id();
        String sourceId = userId.toString();
        Optional<ChatwootContact> existing = findExisting(userId, phone, sourceId);
        if (existing.isEmpty()) {
            try {
                ChatwootContact created = chatwootClient.createContact(fullName != null ? fullName : phone, phone,
                        appIdentifier(userId), Map.of(APP_USER_ID_ATTRIBUTE, userId.toString()), inboxId, sourceId);
                linkRepository.save(new AppContactLink(userId, created.id()));
                log.info("Created Chatwoot contact {} for userId={}", created.id(), userId);
                return created.id();
            } catch (ChatwootException e) {
                if (!e.isUnprocessable()) {
                    throw e;
                }
                // A bridge created this person (same phone) between our lookup and create.
                existing = findExisting(userId, phone, sourceId);
                if (existing.isEmpty()) {
                    throw e;
                }
            }
        }

        ChatwootContact contact = existing.get();
        chatwootClient.updateContact(contact.id(), nameToSet(contact, fullName),
                Map.of(APP_USER_ID_ATTRIBUTE, userId.toString()));
        chatwootClient.addContactInbox(contact.id(), inboxId, sourceId);
        linkRepository.save(new AppContactLink(userId, contact.id()));
        log.info("Attached userId={} to existing Chatwoot contact {}", userId, contact.id());
        return contact.id();
    }

    /**
     * Most reliable key first. The app channel (contact_inbox «Приложение», source_id = userId) follows the person
     * through a manual merge in Chatwoot, whatever phone the resulting contact keeps; then the phone; then the
     * identifier of a contact this service created.
     */
    private Optional<ChatwootContact> findExisting(UUID userId, String phone, String sourceId) {
        Optional<ChatwootContact> byChannel = chatwootClient.findContactIdBySourceId(appInbox.identifier(), sourceId)
                .flatMap(chatwootClient::findContact);
        if (byChannel.isPresent()) {
            return byChannel;
        }
        Optional<ChatwootContact> byPhone = chatwootClient.findContactByPhone(phone);
        return byPhone.isPresent() ? byPhone : chatwootClient.findContactByIdentifier(appIdentifier(userId));
    }

    /**
     * E.164 the way the bridges write it, so that the exact phone match finds the same person: digits with a leading
     * {@code +}, a Russian {@code 8XXXXXXXXXX} as {@code +7XXXXXXXXXX}. Null if it is not a phone.
     */
    static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 10 || digits.length() > 15) {
            return null;
        }
        if (digits.length() == 11 && digits.startsWith("8")) {
            digits = "7" + digits.substring(1);
        }
        return "+" + digits;
    }

    private void syncName(ChatwootContact contact, String fullName) {
        String name = nameToSet(contact, fullName);
        if (name != null) {
            chatwootClient.updateContact(contact.id(), name, Map.of());
        }
    }

    /** The profile name replaces only an empty or bridge-generated one — never a name an agent typed. */
    static String nameToSet(ChatwootContact contact, String fullName) {
        if (fullName == null || fullName.equals(contact.name())) {
            return null;
        }
        String current = contact.name();
        boolean placeholder = current == null || current.isBlank()
                || current.equals(contact.phoneNumber())
                || BRIDGE_PLACEHOLDER_NAME.matcher(current.trim()).matches();
        return placeholder ? fullName : null;
    }

    private static String appIdentifier(UUID userId) {
        return "app:" + userId;
    }
}
