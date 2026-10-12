package com.rewardsiq.rule;

import com.rewardsiq.common.error.ApiException;
import org.springframework.http.HttpStatus;

/** An action that isn't allowed in the rule's current state. */
public class RuleStateException extends ApiException {

    public RuleStateException(String errorCode, String message) {
        super(errorCode, HttpStatus.CONFLICT, message);
    }
}
