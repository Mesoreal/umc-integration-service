package ru.provless.umc.client.ocr;

public interface OcrClient {

    /**
     * Runs OCR over the given file and returns the recognized text.
     * Implementations should treat "no text found" as an empty {@link OcrResult#text()},
     * not an exception — {@link ru.provless.umc.exception.OcrException} is reserved for
     * actual submit/poll/network failures so the caller can fail-open.
     */
    OcrResult recognize(byte[] fileBytes, String contentType);
}
