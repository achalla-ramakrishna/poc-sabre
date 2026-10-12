package com.rewardsiq.common.error;

import org.springframework.http.HttpStatus;

/** Base of all expected errors: a stable error code plus the HTTP status it maps to. */
public abstract class ApiException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    protected ApiException(String errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
