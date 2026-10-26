package ru.provless.integration.chatwoot;

import org.springframework.http.HttpStatus;
import ru.provless.integration.common.error.ApiException;
import ru.provless.integration.common.error.ErrorCode;

/** {@code chatwoot.enabled=false} — the environment has no Chatwoot configured. */
public class ChatwootDisabledException extends ApiException {

    public ChatwootDisabledException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.CHATWOOT_DISABLED, "Chatwoot integration is disabled");
    }
}
