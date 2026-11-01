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

    public record Department(String key, String label, String inboxName) {
    }

    public Optional<Department> byLabel(String label) {
        return departments.stream().filter(d -> d.label().equalsIgnoreCase(label)).findFirst();
    }
}
