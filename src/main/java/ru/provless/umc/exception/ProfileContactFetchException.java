package ru.provless.umc.exception;

import org.springframework.http.HttpStatus;

/** Could not fetch the client's contact info (phone/name) from profile-service's internal API. */
public class ProfileContactFetchException extends ApiException {

    public ProfileContactFetchException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, ErrorCode.PROFILE_FILE_FETCH_FAILED, message, cause);
    }
}
