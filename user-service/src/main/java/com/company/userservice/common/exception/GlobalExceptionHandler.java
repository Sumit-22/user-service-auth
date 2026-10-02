package com.company.userservice.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e, HttpServletRequest r) {
        String msg=e.getBindingResult().getFieldErrors().stream()
            .map(x -> x.getField()+": "+x.getDefaultMessage()).collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST,msg,r);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> other(Exception e, HttpServletRequest r) {
        log.error("Unhandled exception while processing {}", r.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,"Internal server error",r);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus s,String m,HttpServletRequest r){
        return ResponseEntity.status(s).body(new ErrorResponse(Instant.now(),s.value(),s.getReasonPhrase(),m,r.getRequestURI()));
    }
}
