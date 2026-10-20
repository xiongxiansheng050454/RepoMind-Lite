package com.repomind.lite.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import com.repomind.lite.errors.BusinessException;
import com.repomind.lite.errors.FailureType;

@RestControllerAdvice
public class ApiExceptionAdvice {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionAdvice.class);

    public record ErrorResponse(String code, String message) {
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException ex) {
        return ResponseEntity.status(status(ex.type())).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", "Request parameters are invalid"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Void> missingResource() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    ResponseEntity<Void> asyncTimeout() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ex) {
        log.error("Request failed", ex);
        return ResponseEntity.status(500).body(new ErrorResponse("INTERNAL_ERROR", "The request could not be completed"));
    }

    private static HttpStatus status(FailureType type) {
        return switch (type) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
