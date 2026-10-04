package com.company.userservice.ratelimit;

/**
 * @param remaining tokens left after this request, or {@code -1} when not enforced (limiter disabled, or Redis down with ALLOW)
 */
public record RateLimitDecision(boolean allowed, long limit, long remaining, long retryAfterMillis) {
    static RateLimitDecision bypass(long limit){ return new RateLimitDecision(true, limit, -1, 0); }

    public boolean enforced(){ return remaining>=0; }

    public long retryAfterSeconds(){ return Math.max(1, (retryAfterMillis+999)/1000); }
}
