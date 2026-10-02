package com.company.userservice.auth.service;

import com.company.userservice.auth.entity.RefreshToken;
import com.company.userservice.auth.repository.RefreshTokenRepository;
import com.company.userservice.common.exception.ApiException;
import com.company.userservice.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
public class RefreshTokenService {
    private final RefreshTokenRepository repo;
    private final long ttlDays;
    private final SecureRandom random=new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repo,
        @Value("${security.jwt.refresh-token-days}") long ttlDays) {
        this.repo=repo; this.ttlDays=ttlDays;
    }

    @Transactional
    public String create(User user) {
        String raw=randomToken();
        RefreshToken t=new RefreshToken();
        t.setUser(user); t.setTokenHash(hash(raw));
        t.setExpiresAt(Instant.now().plus(ttlDays, java.time.temporal.ChronoUnit.DAYS));
        t.setCreatedAt(Instant.now());
        repo.save(t);
        return raw;
    }

    @Transactional
    public Rotation rotate(String raw) {
        RefreshToken old=repo.findByTokenHash(hash(raw))
            .orElseThrow(()->new ApiException(HttpStatus.UNAUTHORIZED,"Invalid refresh token"));
        if(old.isRevoked() || old.getExpiresAt().isBefore(Instant.now()))
            throw new ApiException(HttpStatus.UNAUTHORIZED,"Refresh token expired or revoked");

        old.setRevoked(true);
        String next=randomToken();
        old.setReplacedByHash(hash(next));
        repo.save(old);

        return new Rotation(old.getUser(),next);
    }

    @Transactional
    public void revoke(String raw) {
        repo.findByTokenHash(hash(raw)).ifPresent(t->{t.setRevoked(true);repo.save(t);});
    }

    @Transactional
    public void revokeAll(UUID userId){ repo.revokeAllForUser(userId); }

    public String hash(String value) {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e){ throw new IllegalStateException(e); }
    }

    private String randomToken() {
        byte[] b=new byte[64]; random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public record Rotation(User user,String refreshToken){}
}
