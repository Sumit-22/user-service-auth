package com.company.userservice.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    // Redis is intentionally available for distributed rate limiting,
    // revocation metadata and other cross-instance state.
}
