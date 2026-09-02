package ru.provless.umc.client.amocrm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.provless.umc.entity.AmoCrmToken;
import ru.provless.umc.exception.AmoCrmException;
import ru.provless.umc.repository.AmoCrmTokenRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * amoCRM API v4 client (OAuth2 refresh-token flow + contacts/leads) for stage 6 of the
 * plan — creating a deal in the "Проверка документов" pipeline once precheck passes.
 *
 * NOTE: this has not been exercised against a real amoCRM account yet (real
 * AMOCRM_* values arrive separately from Yandex's). The OAuth2 token exchange and the
 * contacts/leads request shapes follow amoCRM's published v4 docs, but field-level
 * quirks (search matching, custom field value formats) are the most likely thing to
 * need adjustment on first real run — check {@link AmoCrmException} messages, they
 * include the raw response body.
 */
@Slf4j
@Component
public class AmoCrmClient {

    @Value("${amocrm.subdomain}")
    private String subdomain;

    @Value("${amocrm.client-id}")
    private String clientId;

    @Value("${amocrm.client-secret}")
    private String clientSecret;

    @Value("${amocrm.redirect-uri}")
    private String redirectUri;

    @Value("${amocrm.refresh-token}")
    private String seedRefreshToken;

    @Value("${amocrm.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${amocrm.read-timeout-ms:10000}")
    private int readTimeoutMs;

    private final AmoCrmTokenRepository tokenRepository;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private RestClient restClient;
    private String baseUrl;

    public AmoCrmClient(AmoCrmTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @PostConstruct
    void init() {
        baseUrl = "https://" + subdomain + ".amocrm.ru";
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", "umc-integration-service")
                .build();
    }

    /** Finds a contact by phone, creating one if none matches. Returns the amoCRM contact id. */
    public long findOrCreateContact(String phone, String fullName) {
        Long existing = searchContactByPhone(phone);
        if (existing != null) {
            return existing;
        }
        return createContact(phone, fullName);
    }

    public long createLead(CreateLeadRequest request) {
        ArrayNode body = jsonMapper.createArrayNode();
        ObjectNode lead = body.addObject();
        lead.put("name", "Проверка документа: " + request.documentTypeLabel());
        lead.put("pipeline_id", pipelineId());
        lead.putObject("_embedded").putArray("contacts").addObject().put("id", request.contactId());

        ArrayNode customFields = lead.putArray("custom_fields_values");
        addTextField(customFields, fieldDocumentTypeId(), request.documentTypeLabel());
        addTextField(customFields, fieldDocumentIdId(), request.documentId().toString());
        addTextField(customFields, fieldFileLinkId(), request.fileLink());
        if (request.ocrConfidence() != null) {
            addNumberField(customFields, fieldOcrConfidenceId(), request.ocrConfidence());
        }

        JsonNode response = post("/api/v4/leads", body);
        JsonNode leads = response.path("_embedded").path("leads");
        if (!leads.isArray() || leads.isEmpty()) {
            throw new AmoCrmException("amoCRM lead creation returned no lead: " + response, null);
        }
        return leads.get(0).path("id").asLong();
    }

    private Long searchContactByPhone(String phone) {
        JsonNode response = get("/api/v4/contacts?query=" + java.net.URLEncoder.encode(phone, java.nio.charset.StandardCharsets.UTF_8));
        if (response == null) {
            return null; // 204 No Content — amoCRM's way of saying "no matches"
        }
        JsonNode contacts = response.path("_embedded").path("contacts");
        if (contacts.isArray() && !contacts.isEmpty()) {
            return contacts.get(0).path("id").asLong();
        }
        return null;
    }

    private long createContact(String phone, String fullName) {
        ArrayNode body = jsonMapper.createArrayNode();
        ObjectNode contact = body.addObject();
        contact.put("name", fullName != null && !fullName.isBlank() ? fullName : phone);

        ArrayNode customFields = contact.putArray("custom_fields_values");
        ObjectNode phoneField = customFields.addObject();
        phoneField.put("field_code", "PHONE");
        ObjectNode phoneValue = phoneField.putArray("values").addObject();
        phoneValue.put("value", phone);
        phoneValue.put("enum_code", "WORK");

        JsonNode response = post("/api/v4/contacts", body);
        JsonNode contacts = response.path("_embedded").path("contacts");
        if (!contacts.isArray() || contacts.isEmpty()) {
            throw new AmoCrmException("amoCRM contact creation returned no contact: " + response, null);
        }
        return contacts.get(0).path("id").asLong();
    }

    private void addTextField(ArrayNode customFields, long fieldId, String value) {
        ObjectNode field = customFields.addObject();
        field.put("field_id", fieldId);
        field.putArray("values").addObject().put("value", value);
    }

    private void addNumberField(ArrayNode customFields, long fieldId, BigDecimal value) {
        ObjectNode field = customFields.addObject();
        field.put("field_id", fieldId);
        field.putArray("values").addObject().put("value", value);
    }

    // ─── HTTP + OAuth2 ──────────────────────────────────────────────────────

    private JsonNode get(String path) {
        try {
            var response = restClient.get()
                    .uri(baseUrl + path)
                    .header("Authorization", "Bearer " + accessToken())
                    .retrieve()
                    .toEntity(String.class);
            if (response.getStatusCode().value() == 204 || response.getBody() == null || response.getBody().isBlank()) {
                return null;
            }
            return jsonMapper.readTree(response.getBody());
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return null;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            throw new AmoCrmException("amoCRM GET " + path + " failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (AmoCrmException e) {
            throw e;
        } catch (Exception e) {
            throw new AmoCrmException("amoCRM GET " + path + " failed", e);
        }
    }

    private JsonNode post(String path, JsonNode body) {
        try {
            String responseBody = restClient.post()
                    .uri(baseUrl + path)
                    .header("Authorization", "Bearer " + accessToken())
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return jsonMapper.readTree(responseBody);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            throw new AmoCrmException("amoCRM POST " + path + " failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new AmoCrmException("amoCRM POST " + path + " failed", e);
        }
    }

    String accessToken() {
        AmoCrmToken token = tokenRepository.findById((short) 1)
                .orElseGet(() -> seedToken());

        if (token.getAccessToken() != null && token.getExpiresAt() != null
                && token.getExpiresAt().isAfter(OffsetDateTime.now().plusMinutes(1))) {
            return token.getAccessToken();
        }
        return refresh(token);
    }

    private AmoCrmToken seedToken() {
        AmoCrmToken token = new AmoCrmToken();
        token.setRefreshToken(seedRefreshToken);
        return tokenRepository.save(token);
    }

    private String refresh(AmoCrmToken token) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("client_id", clientId);
        body.put("client_secret", clientSecret);
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", token.getRefreshToken());
        body.put("redirect_uri", redirectUri);

        try {
            String responseBody = restClient.post()
                    .uri(baseUrl + "/oauth2/access_token")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode json = jsonMapper.readTree(responseBody);

            String newAccessToken = json.path("access_token").asText(null);
            String newRefreshToken = json.path("refresh_token").asText(null);
            int expiresIn = json.path("expires_in").asInt(0);
            if (newAccessToken == null || newRefreshToken == null) {
                throw new AmoCrmException("amoCRM token refresh returned no tokens: " + responseBody, null);
            }

            token.setAccessToken(newAccessToken);
            // amoCRM invalidates the old refresh_token as soon as it's used — persist the
            // new one immediately, or the *next* refresh will fail with an already-dead token.
            token.setRefreshToken(newRefreshToken);
            token.setExpiresAt(OffsetDateTime.now().plusSeconds(expiresIn));
            tokenRepository.save(token);
            return newAccessToken;
        } catch (AmoCrmException e) {
            throw e;
        } catch (Exception e) {
            throw new AmoCrmException("Failed to refresh amoCRM OAuth2 token", e);
        }
    }

    @Value("${amocrm.pipeline-id}")
    private String pipelineIdRaw;

    private long pipelineId() {
        return Long.parseLong(pipelineIdRaw);
    }

    @Value("${amocrm.field-document-type-id}")
    private String fieldDocumentTypeIdRaw;

    @Value("${amocrm.field-document-id-id}")
    private String fieldDocumentIdIdRaw;

    @Value("${amocrm.field-file-link-id}")
    private String fieldFileLinkIdRaw;

    @Value("${amocrm.field-ocr-confidence-id}")
    private String fieldOcrConfidenceIdRaw;

    private long fieldDocumentTypeId() {
        return Long.parseLong(fieldDocumentTypeIdRaw);
    }

    private long fieldDocumentIdId() {
        return Long.parseLong(fieldDocumentIdIdRaw);
    }

    private long fieldFileLinkId() {
        return Long.parseLong(fieldFileLinkIdRaw);
    }

    private long fieldOcrConfidenceId() {
        return Long.parseLong(fieldOcrConfidenceIdRaw);
    }

    public record CreateLeadRequest(long contactId, java.util.UUID documentId, String documentTypeLabel,
                                      String fileLink, BigDecimal ocrConfidence) {
    }
}
