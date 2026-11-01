package ru.provless.integration.chatwoot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chatwoot application API (v1, {@code api_access_token} of a dedicated service user) and the public API of the
 * app inbox. Only the calls the app-contact sync and the app chat connector need; request/response shapes checked
 * against Chatwoot 4.19.1 (controllers, builders and jbuilder views of contacts, contact_inboxes, inboxes,
 * conversations, messages, agents).
 */
@Slf4j
@Component
public class ChatwootClient {

    @Value("${chatwoot.base-url}")
    private String baseUrl;

    @Value("${chatwoot.account-id}")
    private String accountIdRaw;

    @Value("${chatwoot.api-token}")
    private String apiToken;

    @Value("${chatwoot.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${chatwoot.read-timeout-ms:10000}")
    private int readTimeoutMs;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private RestClient restClient;

    @PostConstruct
    void init() {
        // java.net.http, not HttpURLConnection: the latter can't send PATCH (message status, inbox webhook).
        // HTTP/1.1 only: Chatwoot (Puma) speaks nothing else, and a cleartext h2c upgrade attempt breaks uploads.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build());
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("api_access_token", apiToken)
                .defaultHeader("User-Agent", "integration-service")
                .build();
        log.info("ChatwootClient initialized: baseUrl={} accountId={} token_present={}",
                baseUrl, accountIdRaw, apiToken != null && !apiToken.isBlank());
    }

    // ─── Contacts ───────────────────────────────────────────────────────────

    public Optional<ChatwootContact> findContact(long contactId) {
        JsonNode json = call(HttpMethod.GET, "/contacts/" + contactId, null, true);
        return json == null ? Optional.empty() : Optional.of(toContact(json.path("payload")));
    }

    /**
     * Exact phone match. The filter endpoint compares with {@code =} after normalizing to
     * {@code +digits}; {@code /contacts/search} is a substring search and would also match longer numbers.
     */
    public Optional<ChatwootContact> findContactByPhone(String phone) {
        ObjectNode body = jsonMapper.createObjectNode();
        ObjectNode condition = body.putArray("payload").addObject();
        condition.put("attribute_key", "phone_number");
        condition.put("filter_operator", "equal_to");
        condition.putArray("values").add(phone);
        condition.putNull("query_operator");

        JsonNode payload = call(HttpMethod.POST, "/contacts/filter", body, false).path("payload");
        return payload.isArray() && !payload.isEmpty() ? Optional.of(toContact(payload.get(0))) : Optional.empty();
    }

    /** Exact identifier match on top of the substring search endpoint. */
    public Optional<ChatwootContact> findContactByIdentifier(String identifier) {
        String query = java.net.URLEncoder.encode(identifier, java.nio.charset.StandardCharsets.UTF_8);
        JsonNode payload = call(HttpMethod.GET, "/contacts/search?q=" + query, null, false).path("payload");
        for (JsonNode contact : payload) {
            if (identifier.equals(contact.path("identifier").asText(null))) {
                return Optional.of(toContact(contact));
            }
        }
        return Optional.empty();
    }

    /** Creates the contact together with its contact_inbox ({@code inboxId}, {@code sourceId}). */
    public ChatwootContact createContact(String name, String phone, String identifier,
                                         Map<String, Object> customAttributes, long inboxId, String sourceId) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("name", name);
        body.put("phone_number", phone);
        body.put("identifier", identifier);
        body.set("custom_attributes", jsonMapper.valueToTree(customAttributes));
        body.put("inbox_id", inboxId);
        body.put("source_id", sourceId);
        JsonNode json = call(HttpMethod.POST, "/contacts", body, false);
        return toContact(json.path("payload").path("contact"));
    }

    /** {@code name} null = leave as is. Chatwoot merges {@code custom_attributes} into the existing ones. */
    public void updateContact(long contactId, String name, Map<String, Object> customAttributes) {
        ObjectNode body = jsonMapper.createObjectNode();
        if (name != null) {
            body.put("name", name);
        }
        body.set("custom_attributes", jsonMapper.valueToTree(customAttributes));
        call(HttpMethod.PUT, "/contacts/" + contactId, body, false);
    }

    /** Idempotent on Chatwoot's side: an existing (contact, inbox, source_id) row is returned as is. */
    public void addContactInbox(long contactId, long inboxId, String sourceId) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("inbox_id", inboxId);
        body.put("source_id", sourceId);
        call(HttpMethod.POST, "/contacts/" + contactId + "/contact_inboxes", body, false);
    }

    /**
     * The contact that owns {@code sourceId} in an API inbox. The application API has no lookup by contact_inbox;
     * the public API of the inbox has ({@code inboxIdentifier} — the channel's identifier, not its id).
     */
    public Optional<Long> findContactIdBySourceId(String inboxIdentifier, String sourceId) {
        String uri = "/public/api/v1/inboxes/" + encode(inboxIdentifier) + "/contacts/" + encode(sourceId);
        JsonNode json = send(HttpMethod.GET, uri, null, true, "GET public contact");
        return json == null ? Optional.empty() : Optional.of(json.path("id").asLong());
    }

    // ─── Inboxes ────────────────────────────────────────────────────────────

    /** The channel identifier of an API inbox — the key of its public API. */
    public String findInboxIdentifier(long inboxId) {
        return call(HttpMethod.GET, "/inboxes/" + inboxId, null, false).path("inbox_identifier").asText();
    }

    public Optional<Long> findInboxIdByName(String name) {
        for (JsonNode inbox : call(HttpMethod.GET, "/inboxes", null, false).path("payload")) {
            if (name.equals(inbox.path("name").asText(null))) {
                return Optional.of(inbox.path("id").asLong());
            }
        }
        return Optional.empty();
    }

    /** API channel inbox. No webhook yet — app messages (BE-19) will add one. */
    public long createApiInbox(String name) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("name", name);
        ObjectNode channel = body.putObject("channel");
        channel.put("type", "api");
        channel.put("webhook_url", "");
        return call(HttpMethod.POST, "/inboxes", body, false).path("id").asLong();
    }

    public void renameInbox(long inboxId, String name) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("name", name);
        call(HttpMethod.PATCH, "/inboxes/" + inboxId, body, false);
    }

    /** Sets the inbox's webhook (API channel); the connector points it at itself on start. */
    public void setInboxWebhook(long inboxId, String webhookUrl) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.putObject("channel").put("webhook_url", webhookUrl);
        call(HttpMethod.PATCH, "/inboxes/" + inboxId, body, false);
    }

    /** Webhook, signing secret (administrators only), identifier and working hours of an API inbox. */
    public ChatwootInboxSettings inboxSettings(long inboxId) {
        JsonNode inbox = call(HttpMethod.GET, "/inboxes/" + inboxId, null, false);
        List<ChatwootInboxSettings.Day> days = new ArrayList<>();
        for (JsonNode day : inbox.path("working_hours")) {
            boolean closed = day.path("closed_all_day").asBoolean(false);
            boolean allDay = day.path("open_all_day").asBoolean(false);
            String open = closed ? null : allDay ? "00:00" : time(day.path("open_hour"), day.path("open_minutes"));
            String close = closed ? null : allDay ? "23:59" : time(day.path("close_hour"), day.path("close_minutes"));
            days.add(new ChatwootInboxSettings.Day(day.path("day_of_week").asInt(), open, close));
        }
        return new ChatwootInboxSettings(inbox.path("id").asLong(), textOrNull(inbox, "webhook_url"),
                textOrNull(inbox, "secret"), textOrNull(inbox, "inbox_identifier"),
                inbox.path("working_hours_enabled").asBoolean(false), textOrNull(inbox, "timezone"), days);
    }

    // ─── Conversations and messages (app chat connector) ────────────────────

    /**
     * A new conversation of the contact in the inbox. The API inbox is not locked to a single conversation
     * ({@code lock_to_single_conversation} false by default), so this really creates one — a new request.
     */
    public ChatwootConversation createConversation(long inboxId, long contactId, String sourceId) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("inbox_id", inboxId);
        body.put("contact_id", contactId);
        body.put("source_id", sourceId);
        return toConversation(call(HttpMethod.POST, "/conversations", body, false));
    }

    public Optional<ChatwootConversation> findConversation(long conversationId) {
        JsonNode json = call(HttpMethod.GET, "/conversations/" + conversationId, null, true);
        return json == null ? Optional.empty() : Optional.of(toConversation(json));
    }

    /**
     * The user's message: incoming in the API inbox. {@code sourceId} marks it as ours, so its webhook echo is
     * recognised; {@code inReplyTo} — Chatwoot id of the quoted message.
     *
     * @return Chatwoot id and creation time of the message
     */
    public ChatwootMessageRef createIncomingMessage(long conversationId, String content, String sourceId, Long inReplyTo,
                                                    List<ChatwootUpload> uploads) {
        return createMessage(conversationId, content, "incoming", false, sourceId, inReplyTo, uploads);
    }

    /** A note only agents see — the user's edits and deletions (no editing of messages through the API). */
    public ChatwootMessageRef createPrivateNote(long conversationId, String content, Long inReplyTo) {
        return createMessage(conversationId, content, "outgoing", true, null, inReplyTo, List.of());
    }

    /** {@code delivered} / {@code read} of an agent's message; API inboxes only, the status never goes back. */
    public void updateMessageStatus(long conversationId, long messageId, String status) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("status", status);
        call(HttpMethod.PATCH, "/conversations/" + conversationId + "/messages/" + messageId, body, false);
    }

    public record ChatwootMessageRef(long id, Instant createdAt) {
    }

    /** The latest messages of a conversation (one page, newest last), with their {@code source_id}. */
    public List<ChatwootMessageSummary> recentMessages(long conversationId) {
        List<ChatwootMessageSummary> messages = new ArrayList<>();
        for (JsonNode m : call(HttpMethod.GET, "/conversations/" + conversationId + "/messages", null, false).path("payload")) {
            messages.add(new ChatwootMessageSummary(m.path("id").asLong(), textOrNull(m, "source_id"),
                    epochOrNull(m.path("created_at"))));
        }
        return messages;
    }

    public record ChatwootMessageSummary(long id, String sourceId, Instant createdAt) {
    }

    private ChatwootMessageRef createMessage(long conversationId, String content, String messageType, boolean isPrivate,
                                             String sourceId, Long inReplyTo, List<ChatwootUpload> uploads) {
        String path = "/conversations/" + conversationId + "/messages";
        JsonNode json;
        if (uploads.isEmpty()) {
            ObjectNode body = jsonMapper.createObjectNode();
            if (content != null) {
                body.put("content", content);
            }
            body.put("message_type", messageType);
            body.put("private", isPrivate);
            if (sourceId != null) {
                body.put("source_id", sourceId);
            }
            if (inReplyTo != null) {
                body.putObject("content_attributes").put("in_reply_to", inReplyTo);
            }
            json = call(HttpMethod.POST, path, body, false);
        } else {
            MultipartBodyBuilder parts = new MultipartBodyBuilder();
            if (content != null) {
                parts.part("content", content);
            }
            parts.part("message_type", messageType);
            parts.part("private", Boolean.toString(isPrivate));
            if (sourceId != null) {
                parts.part("source_id", sourceId);
            }
            if (inReplyTo != null) {
                // MessageBuilder parses a JSON string here
                parts.part("content_attributes", "{\"in_reply_to\":" + inReplyTo + "}");
            }
            for (ChatwootUpload upload : uploads) {
                parts.part("attachments[]", new ByteArrayResource(upload.content()) {
                    @Override
                    public String getFilename() {
                        return upload.name();
                    }
                }).contentType(MediaType.parseMediaType(upload.mimeType()));
            }
            json = sendMultipart("/api/v1/accounts/" + accountIdRaw + path, parts.build(), "POST " + path);
        }
        return new ChatwootMessageRef(json.path("id").asLong(), epochOrNull(json.path("created_at")));
    }

    // ─── Agents ─────────────────────────────────────────────────────────────

    public List<ChatwootAgent> listAgents() {
        List<ChatwootAgent> agents = new ArrayList<>();
        for (JsonNode agent : call(HttpMethod.GET, "/agents", null, false)) {
            String name = textOrNull(agent, "available_name");
            agents.add(new ChatwootAgent(agent.path("id").asLong(), name != null ? name : textOrNull(agent, "name"),
                    agent.path("availability_status").asText("offline")));
        }
        return agents;
    }

    // ─── Public API of the app inbox (acts as the contact) ──────────────────

    /** The user saw the conversation now: Chatwoot marks every agent message before it read. */
    public void markSeenByContact(String inboxIdentifier, String sourceId, long conversationId) {
        send(HttpMethod.POST, publicConversation(inboxIdentifier, sourceId, conversationId) + "/update_last_seen",
                null, false, "POST public update_last_seen");
    }

    public void setContactTyping(String inboxIdentifier, String sourceId, long conversationId, boolean typing) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("typing_status", typing ? "on" : "off");
        send(HttpMethod.POST, publicConversation(inboxIdentifier, sourceId, conversationId) + "/toggle_typing",
                body, false, "POST public toggle_typing");
    }

    private static String publicConversation(String inboxIdentifier, String sourceId, long conversationId) {
        return "/public/api/v1/inboxes/" + encode(inboxIdentifier) + "/contacts/" + encode(sourceId)
                + "/conversations/" + conversationId;
    }

    // ─── HTTP ───────────────────────────────────────────────────────────────

    private JsonNode call(HttpMethod method, String path, JsonNode body, boolean notFoundAsNull) {
        return send(method, "/api/v1/accounts/" + accountIdRaw + path, body, notFoundAsNull, method + " " + path);
    }

    private JsonNode send(HttpMethod method, String uri, JsonNode body, boolean notFoundAsNull, String what) {
        try {
            RestClient.RequestBodySpec request = restClient.method(method).uri(uri);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body.toString());
            }
            String response = request.retrieve().body(String.class);
            return response == null || response.isBlank() ? jsonMapper.createObjectNode() : jsonMapper.readTree(response);
        } catch (RestClientResponseException e) {
            if (notFoundAsNull && e.getStatusCode().value() == 404) {
                return null;
            }
            throw new ChatwootException("Chatwoot " + what + " failed: " + e.getStatusCode().value()
                    + " " + e.getResponseBodyAsString(), e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new ChatwootException("Chatwoot " + what + " failed", 0, e);
        }
    }

    private JsonNode sendMultipart(String uri, MultiValueMap<String, HttpEntity<?>> parts, String what) {
        try {
            String response = restClient.post().uri(uri).contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
                    .retrieve().body(String.class);
            return response == null || response.isBlank() ? jsonMapper.createObjectNode() : jsonMapper.readTree(response);
        } catch (RestClientResponseException e) {
            throw new ChatwootException("Chatwoot " + what + " failed: " + e.getStatusCode().value()
                    + " " + e.getResponseBodyAsString(), e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new ChatwootException("Chatwoot " + what + " failed", 0, e);
        }
    }

    private ChatwootConversation toConversation(JsonNode json) {
        JsonNode assignee = json.path("meta").path("assignee");
        boolean hasAssignee = assignee.isObject() && "User".equals(json.path("meta").path("assignee_type").asText(null));
        String assigneeName = hasAssignee ? textOrNull(assignee, "available_name") : null;
        if (hasAssignee && assigneeName == null) {
            assigneeName = textOrNull(assignee, "name");
        }
        return new ChatwootConversation(
                json.path("id").asLong(),
                json.path("inbox_id").asLong(),
                textOrNull(json, "status"),
                hasAssignee ? assignee.path("id").asLong() : null,
                assigneeName,
                epochOrNull(json.path("agent_last_seen_at")),
                textOrNull(json.path("contact_inbox"), "source_id"));
    }

    /** Chatwoot gives times as epoch seconds (0 = never) or as an ISO string, depending on the view. */
    private static Instant epochOrNull(JsonNode value) {
        if (value.isNumber()) {
            long seconds = value.asLong();
            return seconds <= 0 ? null : Instant.ofEpochSecond(seconds);
        }
        if (value.isTextual() && !value.asText().isBlank()) {
            try {
                return OffsetDateTime.parse(value.asText()).toInstant();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    private static String time(JsonNode hour, JsonNode minutes) {
        return String.format("%02d:%02d", hour.asInt(0), minutes.asInt(0));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private ChatwootContact toContact(JsonNode json) {
        Map<String, Object> customAttributes = new LinkedHashMap<>();
        json.path("custom_attributes").fields()
                .forEachRemaining(e -> customAttributes.put(e.getKey(), e.getValue().asText()));
        return new ChatwootContact(
                json.path("id").asLong(),
                textOrNull(json, "name"),
                textOrNull(json, "phone_number"),
                textOrNull(json, "identifier"),
                customAttributes);
    }

    private static String textOrNull(JsonNode json, String field) {
        JsonNode value = json.path(field);
        return value.isNull() || value.isMissingNode() || value.asText().isEmpty() ? null : value.asText();
    }
}
