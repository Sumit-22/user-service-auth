package com.company.userservice.ratelimit;

import com.company.userservice.common.exception.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Distributed token-bucket rate limiter backed by an atomic Redis Lua script.
 * All app instances share the same buckets. If Redis is unavailable, each policy's
 * {@link RateLimitProperties.FailureMode} decides between allowing, a local in-memory bucket, or 503.
 */
@Component
public class RedisRateLimiter {
    private static final Logger log=LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final long OUTAGE_LOG_INTERVAL_NANOS=Duration.ofSeconds(30).toNanos();

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET=
        RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;
    private final MeterRegistry meters;
    private final LocalTokenBuckets local=new LocalTokenBuckets(100_000, Duration.ofHours(1));
    private final AtomicLong lastOutageLog=new AtomicLong(System.nanoTime()-OUTAGE_LOG_INTERVAL_NANOS);

    public RedisRateLimiter(StringRedisTemplate redis, RateLimitProperties props, MeterRegistry meters) {
        this.redis=redis; this.props=props; this.meters=meters;
    }

    /**
     * Consumes one token from the caller's bucket.
     *
     * @param identifier caller identity (IP, user id, email...); hashed before it reaches Redis
     * @throws RateLimitExceededException when the bucket is empty
     * @throws ApiException 503 when Redis is down and the policy is {@code DENY}
     */
    public RateLimitDecision consume(String policyName, String identifier) {
        return enforce(evaluate(policyName, identifier, 1));
    }

    /** Like {@link #consume} but takes no token; use with {@link #recordFailure} to count only failed attempts. */
    public RateLimitDecision check(String policyName, String identifier) {
        return enforce(evaluate(policyName, identifier, 0));
    }

    /** Takes one token without throwing, e.g. after a failed login. */
    public void recordFailure(String policyName, String identifier) {
        evaluate(policyName, identifier, 1);
    }

    /** Refills the caller's bucket, e.g. after a successful login. */
    public void reset(String policyName, String identifier) {
        props.policy(policyName);
        if(!props.enabled()) return;
        String key=key(policyName, identifier);
        local.reset(key);
        try {
            redis.delete(key);
        } catch(DataAccessException e) {
            logOutage(policyName, e);
        }
    }

    private RateLimitDecision evaluate(String policyName, String identifier, long cost) {
        RateLimitProperties.Policy policy=props.policy(policyName);
        if(!props.enabled()) return RateLimitDecision.bypass(policy.capacity());

        String key=key(policyName, identifier);
        RateLimitDecision decision;
        String backend="redis";
        try {
            List<?> r=redis.execute(TOKEN_BUCKET, List.of(key),
                Long.toString(policy.capacity()), Long.toString(policy.refillTokens()),
                Long.toString(policy.refillPeriod().toMillis()), Long.toString(cost));
            decision=new RateLimitDecision(toLong(r.get(0))==1, policy.capacity(), toLong(r.get(1)), toLong(r.get(2)));
        } catch(DataAccessException e) {
            logOutage(policyName, e);
            backend=policy.onRedisFailure().name().toLowerCase();
            decision=switch(policy.onRedisFailure()) {
                case ALLOW -> RateLimitDecision.bypass(policy.capacity());
                case LOCAL -> local.evaluate(key, policy, cost);
                case DENY -> {
                    record(policyName, backend, "unavailable");
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable");
                }
            };
        }
        record(policyName, backend, decision.allowed() ? "allowed" : "rejected");
        return decision;
    }

    private static RateLimitDecision enforce(RateLimitDecision decision) {
        if(!decision.allowed()) throw new RateLimitExceededException(decision);
        return decision;
    }

    private String key(String policyName, String identifier) {
        return props.keyPrefix()+policyName+":"+hash(identifier);
    }

    /** At most one warning per interval so an outage doesn't flood the logs; the metric counts every request. */
    private void logOutage(String policyName, DataAccessException e) {
        long now=System.nanoTime(), last=lastOutageLog.get();
        if(now-last>=OUTAGE_LOG_INTERVAL_NANOS && lastOutageLog.compareAndSet(last, now))
            log.warn("Rate limiter cannot reach Redis, applying per-policy fallback [policy={}]: {}", policyName, e.toString());
    }

    private void record(String policy, String backend, String outcome) {
        meters.counter("rate_limiter.requests", "policy", policy, "backend", backend, "outcome", outcome).increment();
    }

    private static long toLong(Object o){ return ((Number) o).longValue(); }

    /** Keeps raw IPs/emails (PII) out of Redis while giving a stable, fixed-length key. */
    private static String hash(String value) {
        try {
            byte[] d=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 16);
        } catch(NoSuchAlgorithmException e){ throw new IllegalStateException(e); }
    }
}
