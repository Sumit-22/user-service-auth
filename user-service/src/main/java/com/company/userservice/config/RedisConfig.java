package com.company.userservice.config;

import com.company.userservice.user.dto.UserResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import java.time.Duration;

/**
 * Redis-backed Spring Cache.
 *
 * <ul>
 *   <li>Values are stored as typed JSON (no JDK serialization, no polymorphic type info).</li>
 *   <li>Keys look like {@code user-service:v1:users::<uuid>}.</li>
 *   <li>Only caches registered here exist; using an unknown cache name fails fast.</li>
 *   <li>Cache failures are fail-open: a Redis outage degrades to DB reads instead of erroring.</li>
 * </ul>
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(AppCacheProperties.class)
public class RedisConfig implements CachingConfigurer {
    private static final Logger log=LoggerFactory.getLogger(RedisConfig.class);

    @Bean
    RedisCacheManagerBuilderCustomizer redisCacheCustomizer(AppCacheProperties props, ObjectMapper mapper) {
        return builder -> builder
            .disableCreateOnMissingCache()
            .enableStatistics()
            .withCacheConfiguration(CacheNames.USERS,
                cacheConfig(props, props.userTtl(), new Jackson2JsonRedisSerializer<>(mapper, UserResponse.class)));
    }

    private static RedisCacheConfiguration cacheConfig(AppCacheProperties props, Duration ttl,
                                                       Jackson2JsonRedisSerializer<?> serializer) {
        return RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(ttl)
            .disableCachingNullValues()
            .computePrefixWith(name -> props.keyPrefix()+name+"::")
            .serializeValuesWith(SerializationPair.fromSerializer(serializer));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new FailOpenCacheErrorHandler();
    }

    /** Logs and swallows cache errors so the annotated method falls through to its real data source. */
    static class FailOpenCacheErrorHandler implements CacheErrorHandler {
        @Override
        public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
            log.warn("Cache GET failed [cache={}, key={}], falling back to source: {}", cache.getName(), key, e.toString());
        }

        @Override
        public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
            log.warn("Cache PUT failed [cache={}, key={}]: {}", cache.getName(), key, e.toString());
        }

        @Override
        public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
            // A failed evict can leave stale data until TTL expiry, so log at error level.
            log.error("Cache EVICT failed [cache={}, key={}]", cache.getName(), key, e);
        }

        @Override
        public void handleCacheClearError(RuntimeException e, Cache cache) {
            log.error("Cache CLEAR failed [cache={}]", cache.getName(), e);
        }
    }
}
