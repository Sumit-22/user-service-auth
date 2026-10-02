package com.company.userservice.auth.repository;

import com.company.userservice.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.*;
import java.time.Instant;
import java.util.*;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String hash);
    @Modifying
    @Query("delete from RefreshToken r where r.expiresAt < :now or r.revoked=true")
    int deleteExpiredOrRevoked(Instant now);
    @Modifying
    @Query("update RefreshToken r set r.revoked=true where r.user.id=:userId and r.revoked=false")
    int revokeAllForUser(UUID userId);
}
