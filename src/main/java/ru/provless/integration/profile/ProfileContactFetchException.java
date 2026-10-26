package ru.provless.integration.profile;

import ru.provless.integration.common.error.ApiException;
import ru.provless.integration.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Could not fetch the client's contact info (phone/name) from profile-service's internal API. */
public class ProfileContactFetchException extends ApiException {

    public ProfileContactFetchException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, ErrorCode.PROFILE_FILE_FETCH_FAILED, message, cause);
    }
}
