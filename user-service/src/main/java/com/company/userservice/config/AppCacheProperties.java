package com.company.userservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

/**
 * @param keyPrefix prefix for every cache key; bump the version segment when a cached DTO changes shape
 * @param userTtl   time-to-live for cached user profiles
 */
@ConfigurationProperties(prefix="app.cache")
public record AppCacheProperties(
    @DefaultValue("user-service:v1:") String keyPrefix,
    @DefaultValue("10m") Duration userTtl
) {}
