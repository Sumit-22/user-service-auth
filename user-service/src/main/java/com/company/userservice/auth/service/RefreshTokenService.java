package com.company.userservice.auth.service;

import com.company.userservice.auth.entity.RefreshToken;
import com.company.userservice.auth.repository.RefreshTokenRepository;
import com.company.userservice.common.exception.ApiException;
import com.company.userservice.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log=LoggerFactory.getLogger(RefreshTokenService.class);
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

    // noRollbackFor: the revocation on reuse must be committed even though the request fails with 401.
    @Transactional(noRollbackFor=ApiException.class)
    public Rotation rotate(String raw) {
        RefreshToken old=repo.findByTokenHashForUpdate(hash(raw))
            .orElseThrow(()->new ApiException(HttpStatus.UNAUTHORIZED,"Invalid refresh token"));
        if(old.isRevoked()) {
            // Already rotated: either the legitimate client or an attacker holds a stolen copy. We cannot tell
            // which, so end every session of the user. A token revoked by logout has no successor.
            if(old.getReplacedByHash()!=null) {
                UUID userId=old.getUser().getId();
                int revoked=revokeAll(userId);
                log.warn("Refresh token reuse detected for user {}; revoked {} refresh token(s)", userId, revoked);
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED,"Refresh token expired or revoked");
        }
        if(old.getExpiresAt().isBefore(Instant.now()))
            throw new ApiException(HttpStatus.UNAUTHORIZED,"Refresh token expired or revoked");

        old.setRevoked(true);
        String next=create(old.getUser());
        old.setReplacedByHash(hash(next));
        repo.save(old);

        return new Rotation(old.getUser(),next);
    }

    @Transactional
    public void revoke(String raw) {
        // Locked so a logout racing a refresh cannot overwrite the refresh's replaced_by_hash with null.
        repo.findByTokenHashForUpdate(hash(raw)).ifPresent(t->{t.setRevoked(true);repo.save(t);});
    }

    @Transactional
    public int revokeAll(UUID userId){ return repo.revokeAllForUser(userId); }

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
