package ru.provless.integration.chatwoot;

import org.springframework.http.HttpStatus;
import ru.provless.integration.common.error.ApiException;
import ru.provless.integration.common.error.ErrorCode;

/** A Chatwoot API call failed. {@link #getHttpStatus()} is Chatwoot's status, 0 if no response came back. */
public class ChatwootException extends ApiException {

    private final int httpStatus;

    public ChatwootException(String message, int httpStatus, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, ErrorCode.CHATWOOT_FAILED, message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** 422 — Chatwoot's validation error, e.g. "Phone number has already been taken". */
    public boolean isUnprocessable() {
        return httpStatus == 422;
    }
}
