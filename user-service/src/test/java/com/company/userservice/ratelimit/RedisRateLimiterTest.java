package com.company.userservice.ratelimit;

import com.company.userservice.common.exception.ApiException;
import com.company.userservice.ratelimit.RateLimitProperties.FailureMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RedisRateLimiterTest {

    static LettuceConnectionFactory factory(String host, int port) {
        var f=new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        f.afterPropertiesSet();
        return f;
    }

    static RedisRateLimiter limiter(LettuceConnectionFactory f, RateLimitProperties.Policy policy) {
        var props=new RateLimitProperties(true, "test:rl:", Map.of("p", policy));
        return new RedisRateLimiter(new StringRedisTemplate(f), props, new SimpleMeterRegistry());
    }

    @Nested
    class WhenRedisIsDown {
        // Nothing listens on port 1, so every call fails fast with a connection error.
        private final LettuceConnectionFactory down=factory("localhost", 1);

        @AfterEach void close(){ down.destroy(); }

        private RedisRateLimiter limiterWith(FailureMode mode, long capacity) {
            return limiter(down, new RateLimitProperties.Policy(capacity, capacity, Duration.ofMinutes(1), mode));
        }

        @Test
        void allowModeLetsEverythingThrough() {
            var rl=limiterWith(FailureMode.ALLOW, 1);
            rl.consume("p", "k");
            var d=rl.consume("p", "k");
            assertTrue(d.allowed());
            assertFalse(d.enforced());
        }

        @Test
        void localModeStillEnforcesLimitsInMemory() {
            var rl=limiterWith(FailureMode.LOCAL, 2);
            assertEquals(1, rl.consume("p", "alice").remaining());
            assertEquals(0, rl.consume("p", "alice").remaining());
            var e=assertThrows(RateLimitExceededException.class, () -> rl.consume("p", "alice"));
            assertTrue(e.getDecision().retryAfterMillis()>0);
            assertTrue(rl.consume("p", "bob").allowed(), "buckets stay isolated per identity");
        }

        @Test
        void localModeSupportsFailureCountingAndReset() {
            var rl=limiterWith(FailureMode.LOCAL, 1);
            rl.check("p", "alice");
            rl.recordFailure("p", "alice");
            assertThrows(RateLimitExceededException.class, () -> rl.check("p", "alice"));
            rl.reset("p", "alice");
            assertTrue(rl.check("p", "alice").allowed());
        }

        @Test
        void denyModeRejectsWith503() {
            var rl=limiterWith(FailureMode.DENY, 1);
            var e=assertThrows(ApiException.class, () -> rl.consume("p", "k"));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
        }
    }

    @Test
    void disabledLimiterBypassesRedis() {
        var props=new RateLimitProperties(false, "test:rl:",
            Map.of("p", new RateLimitProperties.Policy(1, 1, Duration.ofMinutes(1), FailureMode.DENY)));
        var d=new RedisRateLimiter(null, props, new SimpleMeterRegistry()).consume("p", "k");
        assertTrue(d.allowed());
    }

    @Test
    void unknownPolicyFailsLoudly() {
        var props=new RateLimitProperties(true, "test:rl:", Map.of());
        var rl=new RedisRateLimiter(null, props, new SimpleMeterRegistry());
        assertThrows(IllegalArgumentException.class, () -> rl.consume("missing", "k"));
    }
}
