package com.company.userservice.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the Lua token bucket against a real Redis. Skipped when Docker is unavailable. */
@Testcontainers(disabledWithoutDocker=true)
class RedisTokenBucketTest {

    @Container
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private LettuceConnectionFactory up;

    @BeforeEach void open(){ up=factory(REDIS.getHost(), REDIS.getMappedPort(6379)); up.getConnection().serverCommands().flushAll(); }
    @AfterEach void close(){ up.destroy(); }

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        var rl=limiter(up, new RateLimitProperties.Policy(3, 3, Duration.ofMinutes(1), RateLimitProperties.FailureMode.LOCAL));

        assertEquals(2, rl.consume("p", "alice").remaining());
        assertEquals(1, rl.consume("p", "alice").remaining());
        assertEquals(0, rl.consume("p", "alice").remaining());

        var e=assertThrows(RateLimitExceededException.class, () -> rl.consume("p", "alice"));
        assertTrue(e.getDecision().retryAfterMillis()>0);
        assertTrue(e.getDecision().retryAfterSeconds()<=20, "one token refills in ~20s");
    }

    @Test
    void bucketsAreIsolatedPerIdentity() {
        var rl=limiter(up, new RateLimitProperties.Policy(1, 1, Duration.ofMinutes(1), RateLimitProperties.FailureMode.LOCAL));
        rl.consume("p", "alice");
        assertThrows(RateLimitExceededException.class, () -> rl.consume("p", "alice"));
        assertTrue(rl.consume("p", "bob").allowed());
    }

    @Test
    void tokensRefillOverTime() throws InterruptedException {
        var rl=limiter(up, new RateLimitProperties.Policy(1, 1, Duration.ofMillis(200), RateLimitProperties.FailureMode.LOCAL));
        rl.consume("p", "alice");
        assertThrows(RateLimitExceededException.class, () -> rl.consume("p", "alice"));
        Thread.sleep(300);
        assertTrue(rl.consume("p", "alice").allowed());
    }

    @Test
    void rawIdentifierNeverStoredInRedis() {
        limiter(up, new RateLimitProperties.Policy(5, 5, Duration.ofMinutes(1), RateLimitProperties.FailureMode.LOCAL)).consume("p", "jane@example.com");
        var keys=new StringRedisTemplate(up).keys("test:rl:*");
        assertEquals(1, keys.size());
        assertFalse(keys.iterator().next().contains("jane"));
    }

    @Test
    void checkDoesNotConsumeAndFailuresDrainTheBucket() {
        var rl=limiter(up, new RateLimitProperties.Policy(2, 2, Duration.ofMinutes(1), RateLimitProperties.FailureMode.LOCAL));
        rl.check("p", "alice");
        rl.check("p", "alice");
        assertEquals(2, rl.check("p", "alice").remaining(), "check must not take tokens");

        rl.recordFailure("p", "alice");
        rl.recordFailure("p", "alice");
        assertThrows(RateLimitExceededException.class, () -> rl.check("p", "alice"));
        rl.recordFailure("p", "alice"); // must not throw even when empty
    }

    @Test
    void resetRefillsTheBucket() {
        var rl=limiter(up, new RateLimitProperties.Policy(1, 1, Duration.ofMinutes(1), RateLimitProperties.FailureMode.LOCAL));
        rl.consume("p", "alice");
        assertThrows(RateLimitExceededException.class, () -> rl.consume("p", "alice"));
        rl.reset("p", "alice");
        assertTrue(rl.consume("p", "alice").allowed());
    }

    private static LettuceConnectionFactory factory(String host, int port) {
        return RedisRateLimiterTest.factory(host, port);
    }

    private static RedisRateLimiter limiter(LettuceConnectionFactory f, RateLimitProperties.Policy policy) {
        return RedisRateLimiterTest.limiter(f, policy);
    }
}
