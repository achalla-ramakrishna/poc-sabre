package com.rewardsiq.common.error;

import com.rewardsiq.common.web.CorrelationIdFilter;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Every error leaves the API as RFC 9457 Problem Details with errorCode and correlationId. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RuleValidationException.class)
    ProblemDetail ruleValidation(RuleValidationException ex) {
        log.info("Rule validation failed {}: {}", ex.getErrorCode(), ex.getFieldErrors());
        ProblemDetail pd = problem(ex.getStatus(), ex.getErrorCode(), ex.getMessage());
        pd.setProperty("fieldErrors", ex.getFieldErrors());
        return pd;
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException ex) {
        log.info("{} {}: {}", ex.getStatus().value(), ex.getErrorCode(), ex.getMessage());
        return problem(ex.getStatus(), ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail beanValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField,
                        fe -> fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage(),
                        (a, b) -> a));
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
        pd.setProperty("fieldErrors", fields);
        return pd;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, IllegalArgumentException.class})
    ProblemDetail badRequest(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Malformed request: " + rootMessage(ex));
    }

    /** Writes that change a rule must send If-Match with the ETag they last read. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException ex) {
        if ("If-Match".equalsIgnoreCase(ex.getHeaderName())) {
            return problem(HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED",
                    "Send If-Match with the rule's ETag (from your last read)");
        }
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Missing header " + ex.getHeaderName());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail optimisticLock(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The rule was changed by someone else. Reload and try again.");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate(DuplicateKeyException ex) {
        return problem(HttpStatus.CONFLICT, "DUPLICATE", "A record with the same key already exists");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to perform this action");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong. Quote the correlationId when contacting support.");
    }

    public static ProblemDetail problem(HttpStatus status, String errorCode, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("https://rewardsiq.example/errors/" + errorCode.toLowerCase().replace('_', '-')));
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("errorCode", errorCode);
        pd.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg.lines().findFirst().orElse(msg);
    }
}
