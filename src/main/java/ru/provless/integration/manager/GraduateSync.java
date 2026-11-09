package ru.provless.integration.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.provless.integration.auth.AuthServiceClient;
import ru.provless.integration.profile.ProfileServiceClient;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Graduates of the training centre (front requests S5 §6, decided 09.11.2026): a manager adds the client's phone to Directus
 * «Выпускники»; the service finds the account by the phone (auth-service), writes into the row whether there is one and sends the
 * whole list to profile-service, which marks exactly these users ({@code is_graduate}). A row removed — the mark goes with the next
 * pass. Directus or a service not answering — the pass is skipped, nothing is cleared.
 */
@Slf4j
@Component
public class GraduateSync {

    static final String NO_ACCOUNT = PersonalManagerSync.NO_ACCOUNT;
    static final String ACCOUNT = PersonalManagerSync.ACCOUNT;

    private final DirectusClient directus;
    private final AuthServiceClient auth;
    private final ProfileServiceClient profiles;

    public GraduateSync(DirectusClient directus, AuthServiceClient auth, ProfileServiceClient profiles) {
        this.directus = directus;
        this.auth = auth;
        this.profiles = profiles;
    }

    @Scheduled(initialDelayString = "${personal-manager.poll-initial-delay}", fixedDelayString = "${graduates.poll-interval:PT5M}")
    public void sync() {
        if (!directus.enabled()) {
            return;
        }
        try {
            Set<UUID> users = new LinkedHashSet<>();
            for (DirectusClient.GraduateRow row : directus.graduates()) {
                UUID user = row.phone() == null ? null : auth.findUserByPhone(row.phone()).orElse(null);
                if (user != null) {
                    users.add(user);
                }
                String client = user == null ? NO_ACCOUNT : ACCOUNT;
                if (!client.equals(row.client())) {
                    directus.updateGraduate(row.id(), Map.of("client", client));
                }
            }
            profiles.replaceGraduates(users);
            log.debug("Graduates sync: {} with an account", users.size());
        } catch (RuntimeException e) {
            log.warn("Graduates sync skipped: {}", e.getMessage());
        }
    }
}
