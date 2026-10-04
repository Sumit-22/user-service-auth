package com.company.userservice.ratelimit;

import com.company.userservice.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class RateLimitExceededException extends ApiException {
    private final RateLimitDecision decision;

    public RateLimitExceededException(RateLimitDecision decision) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, retry after "+decision.retryAfterSeconds()+"s");
        this.decision=decision;
    }

    public RateLimitDecision getDecision(){ return decision; }
}
