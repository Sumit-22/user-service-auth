package com.company.userservice.ratelimit;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitInterceptorTest {

    @RateLimit(policy="class-policy", key=RateLimit.KeyType.USER)
    static class Controller {
        void inherited(){}
        @RateLimit(policy="a") @RateLimit(policy="b") void stacked(){}
    }

    private final RedisRateLimiter limiter=mock(RedisRateLimiter.class);
    private final RateLimitInterceptor interceptor=new RateLimitInterceptor(limiter);
    private final MockHttpServletRequest req=new MockHttpServletRequest();
    private final MockHttpServletResponse res=new MockHttpServletResponse();

    @BeforeEach void ip(){ req.setRemoteAddr("10.0.0.7"); }
    @AfterEach void clear(){ SecurityContextHolder.clearContext(); }

    private static HandlerMethod handler(String method) throws Exception {
        return new HandlerMethod(new Controller(), Controller.class.getDeclaredMethod(method));
    }

    @Test
    void methodAnnotationsAllApplyAndHeadersReportTightestBucket() throws Exception {
        when(limiter.consume("a", "ip:10.0.0.7")).thenReturn(new RateLimitDecision(true, 10, 7, 0));
        when(limiter.consume("b", "ip:10.0.0.7")).thenReturn(new RateLimitDecision(true, 5, 2, 0));

        assertTrue(interceptor.preHandle(req, res, handler("stacked")));

        assertEquals("5", res.getHeader(RateLimitInterceptor.LIMIT_HEADER));
        assertEquals("2", res.getHeader(RateLimitInterceptor.REMAINING_HEADER));
        verify(limiter, never()).consume(eq("class-policy"), anyString());
    }

    @Test
    void classAnnotationKeysOnAuthenticatedUser() throws Exception {
        var jwt=Jwt.withTokenValue("t").header("alg", "none").subject("user-42").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        when(limiter.consume(anyString(), anyString())).thenReturn(new RateLimitDecision(true, 100, 99, 0));

        interceptor.preHandle(req, res, handler("inherited"));

        verify(limiter).consume("class-policy", "user:user-42");
    }

    @Test
    void userKeyFallsBackToIpWhenAnonymous() throws Exception {
        when(limiter.consume(anyString(), anyString())).thenReturn(new RateLimitDecision(true, 100, 99, 0));

        interceptor.preHandle(req, res, handler("inherited"));

        verify(limiter).consume("class-policy", "ip:10.0.0.7");
    }

    @Test
    void bypassedDecisionSetsNoHeaders() throws Exception {
        when(limiter.consume(anyString(), anyString())).thenReturn(RateLimitDecision.bypass(100));

        interceptor.preHandle(req, res, handler("inherited"));

        assertNull(res.getHeader(RateLimitInterceptor.REMAINING_HEADER));
    }

    @Test
    void rejectionPropagates() throws Exception {
        when(limiter.consume(anyString(), anyString()))
            .thenThrow(new RateLimitExceededException(new RateLimitDecision(false, 5, 0, 1500)));

        var e=assertThrows(RateLimitExceededException.class, () -> interceptor.preHandle(req, res, handler("inherited")));
        assertEquals(2, e.getDecision().retryAfterSeconds());
    }
}
