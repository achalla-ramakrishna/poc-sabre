package com.rewardsiq.common.error;

import org.springframework.http.HttpStatus;

/** The client's If-Match revision is stale: someone else saved first. */
public class PreconditionFailedException extends ApiException {

    public PreconditionFailedException(String message) {
        super("CONCURRENT_MODIFICATION", HttpStatus.PRECONDITION_FAILED, message);
    }
}
