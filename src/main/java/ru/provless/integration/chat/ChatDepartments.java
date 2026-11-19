package ru.provless.integration.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Optional;

/** {@code chatwoot.chat.departments}: which label sends a request to which inbox (plan-chat-departments.md). */
@ConfigurationProperties(prefix = "chatwoot.chat")
public record ChatDepartments(List<Department> departments) {

    public ChatDepartments {
        departments = departments == null ? List.of() : List.copyOf(departments);
    }

    /** {@code managerRole} — the line under a manager's name in the app chat when «Подпись в чате» in Directus is empty. */
    public record Department(String key, String label, String inboxName, String managerRole) {
    }

    public Optional<Department> byInboxName(String inboxName) {
        return departments.stream().filter(d -> d.inboxName().equals(inboxName)).findFirst();
    }

    public Optional<Department> byKey(String key) {
        return departments.stream().filter(d -> d.key().equals(key)).findFirst();
    }

    public Optional<Department> byLabel(String label) {
        return departments.stream().filter(d -> d.label().equalsIgnoreCase(label)).findFirst();
    }
}
