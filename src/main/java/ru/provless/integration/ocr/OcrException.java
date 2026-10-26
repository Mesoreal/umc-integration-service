package ru.provless.umc.exception;

import org.springframework.http.HttpStatus;

/** Yandex Vision OCR call failed or timed out — the caller (profile-service) should fail-open. */
public class OcrException extends ApiException {

    public OcrException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, ErrorCode.OCR_FAILED, message, cause);
    }
}
