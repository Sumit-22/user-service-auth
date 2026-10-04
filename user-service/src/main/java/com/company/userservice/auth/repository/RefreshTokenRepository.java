package com.company.userservice.auth.repository;

import com.company.userservice.auth.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String hash);

    // SELECT ... FOR UPDATE: concurrent rotations/logouts of the same token are serialized on its row.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshToken r where r.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

    // Revoked rows are kept until they expire: they are the evidence for refresh-token reuse detection.
    @Modifying
    @Query("delete from RefreshToken r where r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken r set r.revoked=true where r.user.id=:userId and r.revoked=false")
    int revokeAllForUser(UUID userId);
}
