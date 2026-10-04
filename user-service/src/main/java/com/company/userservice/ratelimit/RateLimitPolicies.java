package com.company.userservice.ratelimit;

import java.util.List;

/** Policy names; each must be configured under {@code app.rate-limit.policies}. Validated at startup. */
public final class RateLimitPolicies {
    public static final String LOGIN_IP="login-ip";
    /** Failed logins per (account, IP): tight, so a single attacker IP is stopped quickly without locking out the real user. */
    public static final String LOGIN_EMAIL_IP="login-email-ip";
    /** Failed logins per account from all IPs: loose, catches credential stuffing spread across many IPs. */
    public static final String LOGIN_EMAIL="login-email";
    public static final String REGISTER_IP="register-ip";
    public static final String TOKEN_IP="token-ip";
    public static final String USER_API="user-api";

    public static final List<String> ALL=List.of(LOGIN_IP, LOGIN_EMAIL_IP, LOGIN_EMAIL, REGISTER_IP, TOKEN_IP, USER_API);

    private RateLimitPolicies(){}
}
