package com.rewardsiq.common.error;

import org.springframework.http.HttpStatus;

public class NotFoundException extends ApiException {

    public NotFoundException(String what, String id) {
        super("NOT_FOUND", HttpStatus.NOT_FOUND, what + " '" + id + "' was not found");
    }
}
