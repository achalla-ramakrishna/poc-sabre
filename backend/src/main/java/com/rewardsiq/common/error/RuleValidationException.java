package com.rewardsiq.common.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** A business rule (BR-xx) is broken; carries field-level messages for the UI. */
public class RuleValidationException extends ApiException {

    private final Map<String, String> fieldErrors;

    public RuleValidationException(String errorCode, String message, Map<String, String> fieldErrors) {
        super(errorCode, HttpStatus.UNPROCESSABLE_ENTITY, message);
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
