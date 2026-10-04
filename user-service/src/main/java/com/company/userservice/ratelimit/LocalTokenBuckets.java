package com.company.userservice.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;

/**
 * In-memory token buckets used as a per-instance fallback while Redis is unavailable.
 * Same semantics as {@code token_bucket.lua}. Bounded so an attacker rotating identities cannot exhaust the heap;
 * an evicted bucket behaves like a full one, which only makes the fallback slightly more permissive.
 */
class LocalTokenBuckets {
    private final Cache<String, Bucket> buckets;

    LocalTokenBuckets(long maxBuckets, Duration idleExpiry) {
        buckets=Caffeine.newBuilder().maximumSize(maxBuckets).expireAfterAccess(idleExpiry).build();
    }

    RateLimitDecision evaluate(String key, RateLimitProperties.Policy policy, long cost) {
        return buckets.get(key, k -> new Bucket(policy.capacity(), System.nanoTime()))
            .take(policy, cost, System.nanoTime());
    }

    void reset(String key){ buckets.invalidate(key); }

    static final class Bucket {
        private double tokens;
        private long ts;

        Bucket(double tokens, long ts){ this.tokens=tokens; this.ts=ts; }

        synchronized RateLimitDecision take(RateLimitProperties.Policy p, long cost, long now) {
            double rate=p.tokensPerNano();
            tokens=Math.min(p.capacity(), tokens+Math.max(0, now-ts)*rate);
            ts=now;
            long required=Math.max(cost, 1);
            if(tokens>=required) {
                tokens-=cost;
                return new RateLimitDecision(true, p.capacity(), (long) tokens, 0);
            }
            long retryMillis=(long) Math.ceil((required-tokens)/rate/1_000_000);
            return new RateLimitDecision(false, p.capacity(), (long) tokens, retryMillis);
        }
    }
}
