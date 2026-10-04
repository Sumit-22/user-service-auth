package com.company.userservice.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.Set;

/**
 * Enforces {@link RateLimit} annotations before the controller runs (and before the request body is read).
 * Rejections surface as {@link RateLimitExceededException}, rendered by the global exception handler.
 */
public class RateLimitInterceptor implements HandlerInterceptor {
    public static final String LIMIT_HEADER="X-RateLimit-Limit";
    public static final String REMAINING_HEADER="X-RateLimit-Remaining";

    private final RedisRateLimiter limiter;

    public RateLimitInterceptor(RedisRateLimiter limiter){ this.limiter=limiter; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if(!(handler instanceof HandlerMethod hm)) return true;

        RateLimitDecision tightest=null;
        for(RateLimit rl : limitsFor(hm)) {
            RateLimitDecision d=limiter.consume(rl.policy(), identity(rl.key(), req));
            if(d.enforced() && (tightest==null || d.remaining()<tightest.remaining())) tightest=d;
        }
        if(tightest!=null) {
            res.setHeader(LIMIT_HEADER, Long.toString(tightest.limit()));
            res.setHeader(REMAINING_HEADER, Long.toString(tightest.remaining()));
        }
        return true;
    }

    static Set<RateLimit> limitsFor(HandlerMethod hm) {
        Set<RateLimit> limits=AnnotatedElementUtils.findMergedRepeatableAnnotations(hm.getMethod(), RateLimit.class);
        return limits.isEmpty()
            ? AnnotatedElementUtils.findMergedRepeatableAnnotations(hm.getBeanType(), RateLimit.class)
            : limits;
    }

    private static String identity(RateLimit.KeyType type, HttpServletRequest req) {
        if(type==RateLimit.KeyType.USER
            && SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt)
            return "user:"+jwt.getToken().getSubject();
        // Behind a load balancer, set server.forward-headers-strategy so this is the real client IP.
        return "ip:"+req.getRemoteAddr();
    }
}
