package com.company.userservice.config;

/** Central registry of cache names. Every cache must be registered in {@link RedisConfig}. */
public final class CacheNames {
    public static final String USERS="users";

    private CacheNames(){}
}
