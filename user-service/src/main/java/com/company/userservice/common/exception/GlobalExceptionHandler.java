package com.company.userservice.common.exception;

import com.company.userservice.ratelimit.RateLimitDecision;
import com.company.userservice.ratelimit.RateLimitExceededException;
import com.company.userservice.ratelimit.RateLimitInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    record ErrorResponse(Instant timestamp, int status, String error, String message, String path){}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e, HttpServletRequest r) {
        return build(e.getStatus(), e.getMessage(), r);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ErrorResponse> rateLimited(RateLimitExceededException e, HttpServletRequest r, HttpServletResponse res) {
        RateLimitDecision d=e.getDecision();
        // setHeader replaces values the interceptor may already have written for an earlier, looser policy;
        // headers on the ResponseEntity would be appended instead, duplicating them.
        res.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(d.retryAfterSeconds()));
        res.setHeader(RateLimitInterceptor.LIMIT_HEADER, Long.toString(d.limit()));
        res.setHeader(RateLimitInterceptor.REMAINING_HEADER, "0");
        return build(HttpStatus.TOO_MANY_REQUESTS, e.getMessage(), r);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e, HttpServletRequest r) {
        String msg=e.getBindingResult().getFieldErrors().stream()
            .map(x -> x.getField()+": "+x.getDefaultMessage()).collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST,msg,r);
    }

    // Method-security (@PreAuthorize) denials are thrown inside the controller, so without this they would
    // fall through to the catch-all below as 500 instead of reaching Spring Security's 403 handling.
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> accessDenied(AccessDeniedException e, HttpServletRequest r) {
        return build(HttpStatus.FORBIDDEN,"Access denied",r);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> other(Exception e, HttpServletRequest r) {
        log.error("Unhandled exception while processing {}", r.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,"Internal server error",r);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus s,String m,HttpServletRequest r){
        return ResponseEntity.status(s).body(body(s,m,r));
    }

    private ErrorResponse body(HttpStatus s,String m,HttpServletRequest r){
        return new ErrorResponse(Instant.now(),s.value(),s.getReasonPhrase(),m,r.getRequestURI());
    }
}
