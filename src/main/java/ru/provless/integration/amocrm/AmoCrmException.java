package ru.provless.integration.amocrm;

import ru.provless.integration.common.error.ApiException;
import ru.provless.integration.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** amoCRM API call failed (OAuth2, contact lookup/create, or lead creation). */
public class AmoCrmException extends ApiException {

    public AmoCrmException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, ErrorCode.INTERNAL_ERROR, message, cause);
    }
}
