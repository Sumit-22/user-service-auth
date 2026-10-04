package com.company.userservice.auth.service;

import com.company.userservice.auth.entity.RefreshToken;
import com.company.userservice.auth.repository.RefreshTokenRepository;
import com.company.userservice.common.exception.ApiException;
import com.company.userservice.user.entity.User;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTest {

    /** In-memory stand-in for the refresh_tokens table, keyed by token hash. */
    private final Map<String, RefreshToken> table=new HashMap<>();
    private final RefreshTokenRepository repo=mock(RefreshTokenRepository.class);
    private final RefreshTokenService service=new RefreshTokenService(repo, 30);
    private final User user=new User();

    @BeforeEach
    void wireRepo() {
        when(repo.save(any())).thenAnswer(inv -> {
            RefreshToken t=inv.getArgument(0);
            table.put(t.getTokenHash(), t);
            return t;
        });
        when(repo.findByTokenHash(anyString())).thenAnswer(inv -> Optional.ofNullable(table.get(inv.<String>getArgument(0))));
    }

    @Test
    void rotatedTokenCanBeUsedForTheNextRefresh() {
        String first=service.create(user);

        String second=service.rotate(first).refreshToken();
        String third=service.rotate(second).refreshToken();

        assertNotEquals(first, second);
        assertNotEquals(second, third);
        assertSame(user, table.get(service.hash(third)).getUser());
    }

    @Test
    void rotationRevokesOldTokenAndLinksItToTheNewOne() {
        String first=service.create(user);
        String second=service.rotate(first).refreshToken();

        RefreshToken old=table.get(service.hash(first));
        RefreshToken next=table.get(service.hash(second));
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
    void loggedOutTokenIsRejected() {
        String token=service.rotate(service.create(user)).refreshToken();
        service.revoke(token);

        assertThrows(ApiException.class, () -> service.rotate(token));
    }
}
