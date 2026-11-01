package ru.provless.integration.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.provless.integration.chatwoot.ChatwootException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Fetches an attachment of a Chatwoot message. Its {@code data_url} points at Chatwoot's public host; the request goes
 * to the in-cluster Chatwoot ({@code chatwoot.base-url}) with the same path, and follows the redirect to storage.
 */
@Component
public class ChatwootFiles {

    /** Same as the app's upload limit (contract §14.9). */
    static final long MAX_BYTES = 40L * 1024 * 1024;

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ChatwootFiles(@Value("${chatwoot.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public record Fetched(String name, String mimeType, byte[] content) {
    }

    public Fetched fetch(String dataUrl) {
        URI original = URI.create(dataUrl);
        String path = original.getRawPath() + (original.getRawQuery() == null ? "" : "?" + original.getRawQuery());
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(60)).GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new ChatwootException("Chatwoot attachment " + path + ": " + response.statusCode(),
                            response.statusCode(), null);
                }
                byte[] content = body.readNBytes((int) MAX_BYTES + 1);
                if (content.length > MAX_BYTES) {
                    throw new IllegalArgumentException("Chatwoot attachment over " + MAX_BYTES + " bytes: " + path);
                }
                String type = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
                return new Fetched(fileName(original.getRawPath()), type.split(";")[0].trim(), content);
            }
        } catch (IOException e) {
            throw new ChatwootException("Chatwoot attachment " + path + " not fetched", 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatwootException("Interrupted fetching " + path, 0, e);
        }
    }

    /** ActiveStorage URLs end with the file name. */
    static String fileName(String rawPath) {
        String last = rawPath.substring(rawPath.lastIndexOf('/') + 1);
        String name = URLDecoder.decode(last, StandardCharsets.UTF_8);
        return name.isBlank() ? "file" : name;
    }
}
