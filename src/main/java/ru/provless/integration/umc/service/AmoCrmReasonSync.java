package ru.provless.integration.umc.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.provless.integration.amocrm.AmoCrmClient;
import ru.provless.integration.review.RejectionReasons;

import java.util.List;

/**
 * Keeps the values of the deal's list field «Причина отклонения» equal to the document reasons of Directus (front requests S5 §10):
 * one list, edited in Directus only. Runs after the reasons are read and every few minutes; amoCRM not configured — nothing.
 */
@Slf4j
@Component
public class AmoCrmReasonSync {

    private final AmoCrmClient amo;
    private final RejectionReasons reasons;

    public AmoCrmReasonSync(AmoCrmClient amo, RejectionReasons reasons) {
        this.amo = amo;
        this.reasons = reasons;
    }

    @Scheduled(initialDelayString = "${review.amo-reasons-initial-delay:PT1M}", fixedDelayString = "${review.reasons-interval:PT5M}")
    public void sync() {
        if (!amo.reasonFieldConfigured()) {
            return;
        }
        try {
            List<String> texts = reasons.offered(RejectionReasons.Kind.DOCUMENT).stream().map(RejectionReasons.Reason::text).toList();
            if (texts.size() <= 1) {
                return; // only «Другая причина»: Directus not read yet — do not empty the field
            }
            if (amo.syncReasonValues(texts)) {
                log.info("amoCRM «Причина отклонения»: {} values from Directus", texts.size());
            }
        } catch (RuntimeException e) {
            log.warn("amoCRM «Причина отклонения» not synced, next pass retries: {}", e.getMessage());
        }
    }
}
