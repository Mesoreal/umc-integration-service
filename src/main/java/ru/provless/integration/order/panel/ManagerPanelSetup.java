package ru.provless.integration.order.panel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.provless.integration.chatwoot.ChatwootClient;

/**
 * Keeps the Dashboard App «Заказы клиента» of Chatwoot pointed at the panel (with its key): shortly after start and every few
 * minutes, like the inbox webhooks. Nothing to do by hand; no {@code manager-panel.url} or key — nothing is registered.
 */
@Slf4j
@Component
public class ManagerPanelSetup {

    static final String TITLE = "Заказы клиента";

    private final ChatwootClient chatwoot;
    private final boolean enabled;
    private final String url;

    public ManagerPanelSetup(ChatwootClient chatwoot, @Value("${chatwoot.enabled:false}") boolean chatwootEnabled,
                             @Value("${manager-panel.url:}") String url, @Value("${manager-panel.key:}") String key) {
        this.chatwoot = chatwoot;
        this.enabled = chatwootEnabled && !url.isBlank() && !key.isBlank();
        this.url = enabled ? url + (url.contains("?") ? "&" : "?") + "key=" + key : null;
        log.info("Manager panel «{}»: enabled={} (chatwoot={}, url={}, key {})", TITLE, enabled, chatwootEnabled,
                url.isBlank() ? "-" : url, key.isBlank() ? "missing" : "set");
    }

    @Scheduled(initialDelayString = "${chatwoot.chat.poll-initial-delay}", fixedDelayString = "PT10M")
    public void ensure() {
        if (!enabled) {
            return;
        }
        try {
            String[] found = chatwoot.dashboardApps().stream().filter(app -> TITLE.equals(app[1])).findFirst().orElse(null);
            if (found == null) {
                chatwoot.createDashboardApp(TITLE, url);
                log.info("Chatwoot dashboard app «{}» created", TITLE);
            } else if (!url.equals(found[2])) {
                chatwoot.updateDashboardApp(found[0], TITLE, url);
                log.info("Chatwoot dashboard app «{}» pointed at the panel again", TITLE);
            }
        } catch (RuntimeException e) {
            log.warn("Chatwoot dashboard app «{}» not checked, next try in 10 min: {}", TITLE, e.getMessage());
        }
    }
}
