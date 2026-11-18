package ru.provless.integration.order.panel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.provless.integration.chatwoot.ChatwootClient;

/**
 * Keeps the Dashboard Apps of Chatwoot pointed at the panel (with its key): «Заказы клиента» ({@code manager-panel.url}) and
 * «Проверка документов» (the same address with {@code /reviews} instead of {@code /orders}, front requests S5 §10) — shortly after
 * start and every few minutes, like the inbox webhooks. Nothing to do by hand; no {@code manager-panel.url} or key — nothing is
 * registered.
 */
@Slf4j
@Component
public class ManagerPanelSetup {

    static final String TITLE = "Заказы клиента";
    static final String REVIEWS_TITLE = "Проверка документов";

    private final ChatwootClient chatwoot;
    private final boolean enabled;
    private final java.util.Map<String, String> apps = new java.util.LinkedHashMap<>();

    public ManagerPanelSetup(ChatwootClient chatwoot, @Value("${chatwoot.enabled:false}") boolean chatwootEnabled,
                             @Value("${manager-panel.url:}") String url, @Value("${manager-panel.key:}") String key) {
        this.chatwoot = chatwoot;
        this.enabled = chatwootEnabled && !url.isBlank() && !key.isBlank();
        if (enabled) {
            apps.put(TITLE, withKey(url, key));
            apps.put(REVIEWS_TITLE, withKey(url.replace("/manager-panel/orders", "/manager-panel/reviews"), key));
        }
        log.info("Manager panel «{}»: enabled={} (chatwoot={}, url={}, key {})", TITLE, enabled, chatwootEnabled,
                url.isBlank() ? "-" : url, key.isBlank() ? "missing" : "set");
    }

    @Scheduled(initialDelayString = "${chatwoot.chat.poll-initial-delay}", fixedDelayString = "PT10M")
    public void ensure() {
        if (!enabled) {
            return;
        }
        try {
            java.util.List<String[]> existing = chatwoot.dashboardApps();
            apps.forEach((title, url) -> {
                String[] found = existing.stream().filter(app -> title.equals(app[1])).findFirst().orElse(null);
                if (found == null) {
                    chatwoot.createDashboardApp(title, url);
                    log.info("Chatwoot dashboard app «{}» created", title);
                } else if (!url.equals(found[2])) {
                    chatwoot.updateDashboardApp(found[0], title, url);
                    log.info("Chatwoot dashboard app «{}» pointed at the panel again", title);
                }
            });
        } catch (RuntimeException e) {
            log.warn("Chatwoot dashboard apps not checked, next try in 10 min: {}", e.getMessage());
        }
    }

    private static String withKey(String url, String key) {
        return url + (url.contains("?") ? "&" : "?") + "key=" + key;
    }
}
