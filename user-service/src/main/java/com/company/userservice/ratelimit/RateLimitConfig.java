package com.company.userservice.ratelimit;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.util.*;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig implements WebMvcConfigurer {
    private final RedisRateLimiter limiter;

    public RateLimitConfig(RedisRateLimiter limiter){ this.limiter=limiter; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(limiter));
    }

    /** Fails startup if any referenced policy is missing from configuration, instead of failing on first request. */
    @Bean
    SmartInitializingSingleton rateLimitPolicyValidator(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings,
            RateLimitProperties props) {
        return () -> {
            Set<String> referenced=new TreeSet<>(RateLimitPolicies.ALL);
            mappings.getHandlerMethods().values().forEach(hm ->
                RateLimitInterceptor.limitsFor(hm).forEach(rl -> referenced.add(rl.policy())));
            referenced.removeAll(props.policies().keySet());
            if(!referenced.isEmpty())
                throw new IllegalStateException("Rate-limit policies not configured under app.rate-limit.policies: "+referenced);
        };
    }
}
