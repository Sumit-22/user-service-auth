package com.company.userservice.auth.service;

import com.company.userservice.auth.entity.RefreshToken;
import com.company.userservice.auth.repository.RefreshTokenRepository;
import com.company.userservice.common.exception.ApiException;
import com.company.userservice.user.entity.User;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTest {

    /** In-memory stand-in for the refresh_tokens table, keyed by token hash. */
    private final Map<String, RefreshToken> table=new HashMap<>();
    private final RefreshTokenRepository repo=mock(RefreshTokenRepository.class);
    private final RefreshTokenService service=new RefreshTokenService(repo, 30);
    private final User user=userWithId();
    private final User otherUser=userWithId();

    @BeforeEach
    void wireRepo() {
        when(repo.save(any())).thenAnswer(inv -> {
            RefreshToken t=inv.getArgument(0);
            table.put(t.getTokenHash(), t);
            return t;
        });
        when(repo.findByTokenHashForUpdate(anyString()))
            .thenAnswer(inv -> Optional.ofNullable(table.get(inv.<String>getArgument(0))));
        when(repo.revokeAllForUser(any())).thenAnswer(inv -> {
            UUID userId=inv.getArgument(0);
            int revoked=0;
            for(RefreshToken t : table.values()) {
                if(t.getUser().getId().equals(userId) && !t.isRevoked()) { t.setRevoked(true); revoked++; }
            }
            return revoked;
        });
    }

    private static User userWithId() {
        User u=new User();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        return u;
    }

    private RefreshToken row(String raw) { return table.get(service.hash(raw)); }

    @Test
    void rotatedTokenCanBeUsedForTheNextRefresh() {
        String first=service.create(user);

        String second=service.rotate(first).refreshToken();
        String third=service.rotate(second).refreshToken();

        assertNotEquals(first, second);
        assertNotEquals(second, third);
        assertSame(user, row(third).getUser());
    }

    @Test
    void rotationUsesTheLockingQuery() {
        String first=service.create(user);
        service.rotate(first);

        verify(repo).findByTokenHashForUpdate(service.hash(first));
        verify(repo, never()).findByTokenHash(anyString());
    }

    @Test
    void rotationRevokesOldTokenAndLinksItToTheNewOne() {
        String first=service.create(user);
        String second=service.rotate(first).refreshToken();

        RefreshToken old=row(first);
        RefreshToken next=row(second);
        assertTrue(old.isRevoked());
        assertEquals(next.getTokenHash(), old.getReplacedByHash());
        assertFalse(next.isRevoked());
        assertTrue(next.getExpiresAt().isAfter(old.getCreatedAt()));
    }

    @Test
    void oldTokenIsRejectedAfterRotation() {
        String first=service.create(user);
        service.rotate(first);

        var e=assertThrows(ApiException.class, () -> service.rotate(first));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void reusingARotatedTokenRevokesAllSessionsOfThatUserOnly() {
        String first=service.create(user);
        String successor=service.rotate(first).refreshToken();
        String otherSession=service.create(user);
        String otherUsersSession=service.create(otherUser);

        var e=assertThrows(ApiException.class, () -> service.rotate(first));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
        verify(repo).revokeAllForUser(user.getId());
        assertTrue(row(successor).isRevoked(), "the successor of the replayed token must be revoked");
        assertTrue(row(otherSession).isRevoked(), "other sessions of the same user must be revoked");
        assertFalse(row(otherUsersSession).isRevoked(), "other users must not be affected");
        assertThrows(ApiException.class, () -> service.rotate(successor));
    }

    @Test
    void loggedOutTokenIsRejectedWithoutRevokingOtherSessions() {
        String token=service.rotate(service.create(user)).refreshToken();
        String otherSession=service.create(user);
        service.revoke(token);

        var e=assertThrows(ApiException.class, () -> service.rotate(token));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
        verify(repo, never()).revokeAllForUser(any());
        assertFalse(row(otherSession).isRevoked());
    }

    @Test
    void unknownTokenIsRejected() {
        var e=assertThrows(ApiException.class, () -> service.rotate("not-a-real-token"));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
        verify(repo, never()).revokeAllForUser(any());
    }
}
