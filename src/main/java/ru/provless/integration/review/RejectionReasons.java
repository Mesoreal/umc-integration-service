package ru.provless.integration.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.provless.integration.manager.DirectusClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The ready rejection reasons — Directus «Причины отклонения» (front requests S5 §10), read every few minutes and kept in memory;
 * Directus not answering — the last list stays. {@link #OTHER} is always there and needs the manager's comment.
 */
@Slf4j
@Component
public class RejectionReasons {

    public static final String OTHER = "OTHER";
    public static final String OTHER_TEXT = "Другая причина";

    /** What a reason is about: {@code document} (diploma, passport …) or {@code payer} (clinic, sole trader). */
    public enum Kind {
        DOCUMENT("document"), PAYER("payer");

        final String directus;

        Kind(String directus) {
            this.directus = directus;
        }
    }

    public record Reason(String code, String text) {
    }

    private final DirectusClient directus;
    private volatile List<DirectusClient.ReasonRow> rows = List.of();

    public RejectionReasons(DirectusClient directus) {
        this.directus = directus;
    }

    @Scheduled(initialDelayString = "${review.reasons-initial-delay:PT20S}", fixedDelayString = "${review.reasons-interval:PT5M}")
    public void refresh() {
        if (!directus.enabled()) {
            return;
        }
        try {
            rows = List.copyOf(directus.rejectionReasons());
            log.debug("Rejection reasons: {}", rows.size());
        } catch (RuntimeException e) {
            log.warn("Rejection reasons not read from Directus, the last list stays ({}): {}", rows.size(), e.getMessage());
        }
    }

    /** The active reasons of the kind, in the Directus order, then «Другая причина». */
    public List<Reason> offered(Kind kind) {
        if (rows.isEmpty()) {
            refresh();
        }
        List<Reason> list = new ArrayList<>();
        rows.stream().filter(r -> r.active() && r.appliesTo().contains(kind.directus)).forEach(r -> list.add(new Reason(r.code(), r.text())));
        list.add(new Reason(OTHER, OTHER_TEXT));
        return list;
    }

    /** An offered reason of the kind by its code. */
    public Optional<Reason> byCode(Kind kind, String code) {
        return offered(kind).stream().filter(r -> r.code().equals(code)).findFirst();
    }

    /** By the text as amoCRM's list field shows it (the list there is these texts); inactive ones too — a deal may be old. */
    public Optional<Reason> byText(String text) {
        if (text == null) {
            return Optional.empty();
        }
        if (rows.isEmpty()) {
            refresh();
        }
        if (OTHER_TEXT.equalsIgnoreCase(text.trim())) {
            return Optional.of(new Reason(OTHER, OTHER_TEXT));
        }
        return rows.stream().filter(r -> r.text().trim().equalsIgnoreCase(text.trim())).findFirst()
                .map(r -> new Reason(r.code(), r.text()));
    }
}
