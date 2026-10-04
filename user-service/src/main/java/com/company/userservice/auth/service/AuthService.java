package com.company.userservice.auth.service;

import com.company.userservice.auth.dto.*;
import com.company.userservice.common.exception.ApiException;
import com.company.userservice.ratelimit.RateLimitPolicies;
import com.company.userservice.ratelimit.RedisRateLimiter;
import com.company.userservice.user.entity.*;
import com.company.userservice.user.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refresh;
    private final AuthenticationManager authenticationManager;
    private final RedisRateLimiter rateLimiter;

    public AuthService(UserRepository users, RoleRepository roles, PasswordEncoder encoder,
                       JwtService jwt, RefreshTokenService refresh,
                       AuthenticationManager authenticationManager, RedisRateLimiter rateLimiter) {
        this.users=users; this.roles=roles; this.encoder=encoder; this.jwt=jwt;
        this.refresh=refresh; this.authenticationManager=authenticationManager; this.rateLimiter=rateLimiter;
    }

    @Transactional
    public TokenResponse register(RegisterRequest req) {
        if(users.existsByEmailIgnoreCase(req.email()))
            throw new ApiException(HttpStatus.CONFLICT,"Email already registered");

        Role role=roles.findByName("USER").orElseThrow();
        User u=new User();
        u.setEmail(req.email().trim().toLowerCase());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setFirstName(req.firstName().trim());
        u.setLastName(req.lastName().trim());
        u.getRoles().add(role);
        users.save(u);
        return tokens(u);
    }

    // Deliberately not @Transactional: rate-limit calls to Redis and Argon2 verification must not hold a DB
    // connection. The user lookup and refresh-token insert each run in their own short transaction.
    public TokenResponse login(LoginRequest req, String clientIp) {
        String email=req.email().trim().toLowerCase();
        String emailAndIp=email+"|"+clientIp;

        // Only failed attempts count. The tight (account, IP) bucket stops one attacker without locking
        // the real user out from their own IP; the loose per-account bucket catches distributed stuffing.
        rateLimiter.check(RateLimitPolicies.LOGIN_EMAIL_IP, emailAndIp);
        rateLimiter.check(RateLimitPolicies.LOGIN_EMAIL, email);
        try {
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email,req.password()));
        } catch (AuthenticationException e) {
            rateLimiter.recordFailure(RateLimitPolicies.LOGIN_EMAIL_IP, emailAndIp);
            rateLimiter.recordFailure(RateLimitPolicies.LOGIN_EMAIL, email);
            throw new ApiException(HttpStatus.UNAUTHORIZED,"Invalid email or password");
        }
        // Not resetting the per-account bucket: a victim's successful login must not hand attackers a fresh budget.
        rateLimiter.reset(RateLimitPolicies.LOGIN_EMAIL_IP, emailAndIp);
        User u=users.findSecurityUser(req.email()).orElseThrow();
        return tokens(u);
    }

    @Transactional
    public TokenResponse refresh(RefreshRequest req) {
        RefreshTokenService.Rotation rotation=refresh.rotate(req.refreshToken());
        return new TokenResponse(jwt.createAccessToken(rotation.user()),rotation.refreshToken(),
            jwt.expiresInSeconds(),"Bearer");
    }

    @Transactional
    public void logout(RefreshRequest req){ refresh.revoke(req.refreshToken()); }

    private TokenResponse tokens(User u) {
        return new TokenResponse(jwt.createAccessToken(u),refresh.create(u),
            jwt.expiresInSeconds(),"Bearer");
    }
}
