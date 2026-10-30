package ru.provless.integration.chatwoot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Chatwoot application API (v1, {@code api_access_token} of a dedicated service user). Only the
 * calls the app-contact sync needs; request/response shapes checked against Chatwoot 4.19.1
 * (controllers and jbuilder views of contacts, contact_inboxes, inboxes).
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
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
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
