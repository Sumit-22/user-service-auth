package com.company.userservice.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;
import java.util.Map;

/**
 * @param enabled   global kill switch; when false every request is allowed
 * @param keyPrefix prefix for every bucket key in Redis
 * @param policies  named token-bucket policies referenced by {@link RateLimit} and {@link RateLimitPolicies}
 */
@Validated
@ConfigurationProperties(prefix="app.rate-limit")
public record RateLimitProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("user-service:rl:") @NotBlank String keyPrefix,
    Map<String, @Valid Policy> policies
) {
    public RateLimitProperties {
        policies=policies==null ? Map.of() : Map.copyOf(policies);
    }

    public Policy policy(String name) {
        Policy p=policies.get(name);
        if(p==null) throw new IllegalArgumentException("Unknown rate-limit policy: "+name);
        return p;
    }

    /**
     * Token bucket: holds up to {@code capacity} tokens and regains {@code refillTokens}
     * every {@code refillPeriod}. Each request costs one token.
     *
     * @param onRedisFailure what to do when Redis is unavailable
     */
    public record Policy(
        @Positive long capacity,
        @Positive long refillTokens,
        @NotNull Duration refillPeriod,
        @DefaultValue("local") FailureMode onRedisFailure
    ) {
        double tokensPerNano(){ return (double) refillTokens/refillPeriod.toNanos(); }
    }

    public enum FailureMode {
        /** Let every request through (no protection while Redis is down). */
        ALLOW,
        /** Enforce the same limits per instance in memory; the effective limit becomes limit x instances. */
        LOCAL,
        /** Reject with 503 (strongest protection, but the endpoint is down while Redis is). */
        DENY
    }
}
