package ru.provless.integration.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Directus collections of personal managers (created by docs/backend/artifacts/personal-manager/tools/
 * directus_personal_managers.py), as the service user «integration-service» with its static token.
 */
@Slf4j
@Component
public class DirectusClient {

    private static final String CLIENT_MANAGER_FIELDS = String.join(",", "id", "phone", "client", "app_user_id",
            "assigned_by", "assigned_at", "date_created", "date_updated", "manager.id", "manager.name",
            "user_created.first_name", "user_created.last_name", "user_created.email",
            "user_updated.first_name", "user_updated.last_name", "user_updated.email");

    @Value("${directus.url:}")
    private String url;

    @Value("${directus.token:}")
    private String token;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private RestClient restClient;

    @PostConstruct
    void init() {
        // HTTP/1.1: the JDK client's h2c upgrade on plain http gets the stream reset by some servers
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        restClient = RestClient.builder()
                .baseUrl(url.isBlank() ? "http://localhost" : url)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader(HttpHeaders.USER_AGENT, "integration-service")
                .build();
        log.info("Directus (personal managers): enabled={} url={}", enabled(), url);
    }

    public boolean enabled() {
        return !url.isBlank() && !token.isBlank();
    }

    // ─── «Менеджеры» ────────────────────────────────────────────────────────

    public Map<Long, DirectusManager> managers() {
        Map<Long, DirectusManager> managers = new LinkedHashMap<>();
        for (JsonNode m : get("/items/managers?limit=-1&fields=id,name,email,departments,active,avatar_url")) {
            managers.put(m.path("id").asLong(), new DirectusManager(m.path("id").asLong(), text(m, "name"), text(m, "email"),
                    text(m, "departments"), m.path("active").asBoolean(false), text(m, "avatar_url")));
        }
        return managers;
    }

    public void createManager(DirectusManager manager) {
        Map<String, Object> body = fields(manager);
        body.put("id", manager.id());
        send(restClient.post().uri("/items/managers"), body);
    }

    public void updateManager(DirectusManager manager) {
        send(restClient.patch().uri("/items/managers/{id}", manager.id()), fields(manager));
    }

    private static Map<String, Object> fields(DirectusManager manager) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", manager.name());
        body.put("email", manager.email());
        body.put("departments", manager.departments());
        body.put("active", manager.active());
        body.put("avatar_url", manager.avatarUrl());
        return body;
    }

    // ─── «Персональные менеджеры клиентов» ──────────────────────────────────

    public List<ClientManagerRow> clientManagers() {
        List<ClientManagerRow> rows = new ArrayList<>();
        for (JsonNode r : get("/items/client_managers?limit=-1&fields=" + CLIENT_MANAGER_FIELDS)) {
            JsonNode manager = r.path("manager");
            String appUserId = text(r, "app_user_id");
            rows.add(new ClientManagerRow(UUID.fromString(r.path("id").asText()), text(r, "phone"),
                    manager.isObject() ? manager.path("id").asLong() : null, manager.isObject() ? text(manager, "name") : null,
                    text(r, "client"), appUserId == null ? null : UUID.fromString(appUserId), text(r, "assigned_by"),
                    instant(r.path("assigned_at")),
                    editor(r.path("user_created")), instant(r.path("date_created")),
                    editor(r.path("user_updated")), instant(r.path("date_updated"))));
        }
        return rows;
    }

    /** Only the given fields; a null value clears the field. */
    public void updateClientManager(UUID id, Map<String, Object> fields) {
        send(restClient.patch().uri("/items/client_managers/{id}", id), fields);
    }

    /** A row already gone is fine. */
    public void deleteClientManager(UUID id) {
        try {
            restClient.delete().uri("/items/client_managers/{id}", id).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // deleted in the CMS meanwhile
        }
    }

    // ─── HTTP ───────────────────────────────────────────────────────────────

    private JsonNode get(String uri) {
        String body = restClient.get().uri(uri).retrieve().body(String.class);
        try {
            return jsonMapper.readTree(body).path("data");
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable Directus answer to " + uri, e);
        }
    }

    private static void send(RestClient.RequestBodySpec request, Map<String, Object> body) {
        request.contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
    }

    private static Editor editor(JsonNode user) {
        return user.isObject() ? new Editor(text(user, "first_name"), text(user, "last_name"), text(user, "email")) : null;
    }

    /** Directus gives timestamps with a zone ({@code …Z}); a bare local time is taken as UTC. */
    static Instant instant(JsonNode value) {
        String text = value.isTextual() ? value.asText() : null;
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.path(field);
        return value.isNull() || value.isMissingNode() || value.asText().isEmpty() ? null : value.asText();
    }

    public record DirectusManager(long id, String name, String email, String departments, boolean active, String avatarUrl) {
    }

    /** A Directus user who created or changed a row. */
    public record Editor(String firstName, String lastName, String email) {

        /** «Анна Петрова (anna@mesoreal.ru)». */
        public String label() {
            String name = String.join(" ", java.util.stream.Stream.of(firstName, lastName)
                    .filter(s -> s != null && !s.isBlank()).toList());
            if (email == null) {
                return name.isEmpty() ? null : name;
            }
            return name.isEmpty() ? email : name + " (" + email + ")";
        }
    }

    public record ClientManagerRow(UUID id, String phone, Long managerId, String managerName, String client, UUID appUserId,
                                   String assignedBy, Instant assignedAt, Editor createdBy, Instant createdAt,
                                   Editor updatedBy, Instant updatedAt) {
    }
}
