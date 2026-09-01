package ru.provless.umc.client.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.provless.umc.exception.OcrException;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Yandex Vision OCR via the async {@code recognizeTextAsync} endpoint (submit + poll the
 * resulting Yandex Cloud Operation) — used uniformly for photos and PDFs, no per-page
 * branching. See umc/document-verification-plan.md "OCR и фильтр".
 *
 * NOTE: the exact shape of a completed operation's {@code response} field below is built
 * from Yandex's published OCR API docs but has not been exercised against a live key yet
 * (YANDEX_OCR_API_KEY arrives separately) — re-verify {@link #extractFullText} against a
 * real response the first time this runs for real, ocr_raw_text on DocumentReview makes
 * that easy to spot-check.
 */
@Slf4j
@Component
public class YandexOcrClient implements OcrClient {

    private static final String SUBMIT_URL = "https://ocr.api.cloud.yandex.net/ocr/v1/recognizeTextAsync";
    private static final String OPERATION_URL = "https://operation.api.cloud.yandex.net/operations/";

    @Value("${yandex.ocr.api-key}")
    private String apiKey;

    @Value("${yandex.ocr.folder-id}")
    private String folderId;

    @Value("${yandex.ocr.poll-interval-ms:1000}")
    private long pollIntervalMs;

    @Value("${yandex.ocr.poll-deadline-ms:27000}")
    private long pollDeadlineMs;

    @Value("${yandex.ocr.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${yandex.ocr.read-timeout-ms:10000}")
    private int readTimeoutMs;

    private RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Authorization", "Api-Key " + apiKey)
                .defaultHeader("User-Agent", "umc-integration-service")
                .build();
    }

    @Override
    public OcrResult recognize(byte[] fileBytes, String contentType) {
        String operationId = submit(fileBytes, contentType);
        JsonNode operation = poll(operationId);
        return new OcrResult(extractFullText(operation), extractAverageConfidence(operation));
    }

    private String submit(byte[] fileBytes, String contentType) {
        try {
            Map<String, Object> body = Map.of(
                    "mimeType", toYandexMimeType(contentType),
                    "languageCodes", java.util.List.of("ru", "en"),
                    "model", "page",
                    "folderId", folderId,
                    "content", Base64.getEncoder().encodeToString(fileBytes)
            );

            String responseBody = restClient.post()
                    .uri(SUBMIT_URL)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode json = jsonMapper.readTree(responseBody);
            String id = json.path("id").asText(null);
            if (id == null) {
                throw new OcrException("Yandex OCR submit response had no operation id: " + responseBody, null);
            }
            return id;
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException("Failed to submit document to Yandex OCR", e);
        }
    }

    private JsonNode poll(String operationId) {
        Instant deadline = Instant.now().plusMillis(pollDeadlineMs);
        try {
            while (true) {
                String responseBody = restClient.get()
                        .uri(OPERATION_URL + operationId)
                        .retrieve()
                        .body(String.class);

                JsonNode operation = jsonMapper.readTree(responseBody);

                if (operation.path("error").isObject() && !operation.path("error").isEmpty()) {
                    throw new OcrException("Yandex OCR operation failed: " + operation.path("error"), null);
                }
                if (operation.path("done").asBoolean(false)) {
                    return operation;
                }
                if (Instant.now().isAfter(deadline)) {
                    throw new OcrException("Yandex OCR operation " + operationId + " did not complete within " + pollDeadlineMs + "ms", null);
                }
                Thread.sleep(pollIntervalMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException("Interrupted while polling Yandex OCR operation " + operationId, e);
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException("Failed to poll Yandex OCR operation " + operationId, e);
        }
    }

    /**
     * A completed recognizeTextAsync operation's {@code response.textAnnotation.fullText}
     * carries the recognized text. Falls back to an empty string (treated as "no text
     * found", not a failure) if the shape doesn't match what's expected.
     */
    private String extractFullText(JsonNode operation) {
        JsonNode fullText = operation.path("response").path("textAnnotation").path("fullText");
        if (fullText.isMissingNode() || fullText.isNull()) {
            log.warn("Yandex OCR operation {} completed but had no textAnnotation.fullText — treating as empty result",
                    operation.path("id").asText());
            return "";
        }
        return fullText.asText("");
    }

    /**
     * Averages {@code response.textAnnotation.blocks[].lines[].words[].confidence}.
     * Returns {@code null} when the response has no words to average (e.g. blank page) —
     * the caller decides the fallback, since "no confidence" isn't the same as "zero
     * confidence".
     */
    private java.math.BigDecimal extractAverageConfidence(JsonNode operation) {
        JsonNode blocks = operation.path("response").path("textAnnotation").path("blocks");
        if (!blocks.isArray()) {
            return null;
        }
        double sum = 0;
        int count = 0;
        for (JsonNode block : blocks) {
            for (JsonNode line : block.path("lines")) {
                for (JsonNode word : line.path("words")) {
                    JsonNode confidence = word.path("confidence");
                    if (confidence.isNumber()) {
                        sum += confidence.asDouble();
                        count++;
                    }
                }
            }
        }
        if (count == 0) {
            return null;
        }
        return java.math.BigDecimal.valueOf(sum / count).setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private String toYandexMimeType(String contentType) {
        if (contentType == null) {
            return "PDF";
        }
        return switch (contentType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> "JPEG";
            case "image/png" -> "PNG";
            case "application/pdf" -> "PDF";
            // Yandex OCR's documented set is narrower than our upload allow-list
            // (image/heic, image/webp) — pass the raw subtype through uppercased
            // and let Yandex reject it explicitly rather than guess silently.
            default -> contentType.substring(contentType.indexOf('/') + 1).toUpperCase();
        };
    }
}
