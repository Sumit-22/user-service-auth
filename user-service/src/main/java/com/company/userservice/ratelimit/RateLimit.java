package com.company.userservice.ratelimit;

import java.lang.annotation.*;

/**
 * Applies a rate-limit policy to a controller method (or every method of a controller).
 * Repeatable: all policies must allow the request. Method-level annotations replace class-level ones.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(RateLimit.List.class)
public @interface RateLimit {
    /** Policy name from {@link RateLimitPolicies}. */
    String policy();

    /** What identifies the caller's bucket. */
    KeyType key() default KeyType.IP;

    enum KeyType {
        /** Client IP address. */
        IP,
        /** Authenticated user id (JWT subject); falls back to IP for anonymous callers. */
        USER
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface List { RateLimit[] value(); }
}
