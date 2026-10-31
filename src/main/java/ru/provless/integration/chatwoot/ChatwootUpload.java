package ru.provless.integration.chatwoot;

/** A file sent with a message ({@code attachments[]} of the multipart request). */
public record ChatwootUpload(String name, String mimeType, byte[] content) {
}
