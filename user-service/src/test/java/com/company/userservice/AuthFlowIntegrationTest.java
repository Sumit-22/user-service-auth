package com.company.userservice;

import com.company.userservice.auth.dto.*;
import com.company.userservice.auth.repository.RefreshTokenRepository;
import com.company.userservice.auth.service.RefreshTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end over HTTP against real Postgres (Flyway V1-V3) and Redis. Covers what the mocked unit tests cannot:
 * method security, the row lock, and that reuse revocation is committed despite the 401. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker=true)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties="app.rate-limit.enabled=false")
class AuthFlowIntegrationTest {

    static {
        // pgjdbc sends the JVM zone to Postgres on connect; legacy aliases like "Asia/Calcutta" (some Windows
        // JDKs in India) are rejected by Postgres 17. The app container runs in UTC, so test the same way.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17");

    @Container
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static final String PASSWORD="StrongPassword123!";

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired RefreshTokenService refreshTokenService;

    // --- /users/{id} access control ---

    @Test
    void userCanReadOwnProfileButNotAnotherUsers() throws Exception {
        TokenResponse alice=register(uniqueEmail());
        TokenResponse bob=register(uniqueEmail());
        UUID aliceId=userId(alice);

        assertEquals(HttpStatus.OK, getUser(aliceId, alice.accessToken()).getStatusCode());

        var denied=getUser(aliceId, bob.accessToken());
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("Access denied", json.readTree(denied.getBody()).get("message").asText());
    }

    @Test
    void adminWithUserReadCanReadAnyProfile() throws Exception {
        UUID aliceId=userId(register(uniqueEmail()));
        String adminEmail=uniqueEmail();
        TokenResponse admin=register(adminEmail);
        jdbc.update("insert into user_roles(user_id, role_id) select ?, id from roles where name='ADMIN'", userId(admin));
        // Permissions are embedded in the access token at login, so log in again to pick up ADMIN.
        String adminToken=login(adminEmail).accessToken();

        var res=getUser(aliceId, adminToken);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(aliceId.toString(), json.readTree(res.getBody()).get("id").asText());
    }

    @Test
    void regularUserNoLongerHasUserReadPermission() throws Exception {
        TokenResponse alice=register(uniqueEmail());

        var me=json.readTree(get("/api/v1/users/me", alice.accessToken()).getBody());

        assertFalse(me.get("permissions").toString().contains("USER_READ"), "V3 must remove USER -> USER_READ");
    }

    // --- refresh-token rotation, reuse detection and logout ---

    @Test
    void replayingARotatedTokenRevokesEverySessionOfThatUserOnly() {
        String email=uniqueEmail();
        String r1=register(email).refreshToken();
        String loginSession=login(email).refreshToken();
        String otherUsersSession=register(uniqueEmail()).refreshToken();

        var rotated=refresh(r1);
        assertEquals(HttpStatus.OK, rotated.getStatusCode());
        String r2=tokenFrom(rotated).refreshToken();

        assertEquals(HttpStatus.UNAUTHORIZED, refresh(r1).getStatusCode(), "replayed R1");

        // Read straight from the DB: proves the revoke-all was committed although the request failed with 401.
        assertTrue(isRevoked(r2), "successor of the replayed token");
        assertTrue(isRevoked(loginSession), "other session of the same user");
        assertFalse(isRevoked(otherUsersSession), "other users must not be affected");
        assertEquals(HttpStatus.UNAUTHORIZED, refresh(r2).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, refresh(loginSession).getStatusCode());
    }

    @Test
    void loggedOutTokenIsRejectedWithoutEndingOtherSessions() {
        String email=uniqueEmail();
        String r1=register(email).refreshToken();
        String loginSession=login(email).refreshToken();

        assertEquals(HttpStatus.NO_CONTENT,
            http.postForEntity("/api/v1/auth/logout", new RefreshRequest(r1), Void.class).getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED, refresh(r1).getStatusCode());
        assertFalse(isRevoked(loginSession));
        assertEquals(HttpStatus.OK, refresh(loginSession).getStatusCode());
    }

    @Test
    void concurrentRefreshesWithTheSameTokenAreSerialized() throws Exception {
        String r1=register(uniqueEmail()).refreshToken();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<ResponseEntity<String>> call=() -> { start.await(); return refresh(r1); };
            Future<ResponseEntity<String>> a=pool.submit(call);
            Future<ResponseEntity<String>> b=pool.submit(call);
            start.countDown();
            List<ResponseEntity<String>> results=List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));

            // Without the row lock both would read revoked=false and both would get a valid new token.
            List<HttpStatusCode> statuses=results.stream().map(ResponseEntity::getStatusCode).sorted(
                Comparator.comparingInt(HttpStatusCode::value)).toList();
            assertEquals(List.of(HttpStatus.OK, HttpStatus.UNAUTHORIZED), statuses);

            // The loser saw an already-rotated token, which is reuse: the winner's new token is revoked as well.
            String winnersToken=tokenFrom(results.stream()
                .filter(r -> r.getStatusCode()==HttpStatus.OK).findFirst().orElseThrow()).refreshToken();
            assertTrue(isRevoked(winnersToken));
        } finally {
            pool.shutdownNow();
        }
    }

    // --- helpers ---

    private static String uniqueEmail() { return "it-"+UUID.randomUUID()+"@example.com"; }

    private TokenResponse register(String email) {
        var res=http.postForEntity("/api/v1/auth/register",
            new RegisterRequest(email, PASSWORD, "Integration", "Test"), String.class);
        assertEquals(HttpStatus.CREATED, res.getStatusCode(), res.getBody());
        return tokenFrom(res);
    }

    private TokenResponse login(String email) {
        var res=http.postForEntity("/api/v1/auth/login", new LoginRequest(email, PASSWORD), String.class);
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        return tokenFrom(res);
    }

    private ResponseEntity<String> refresh(String refreshToken) {
        return http.postForEntity("/api/v1/auth/refresh", new RefreshRequest(refreshToken), String.class);
    }

    private ResponseEntity<String> get(String path, String accessToken) {
        HttpHeaders headers=new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> getUser(UUID id, String accessToken) {
        return get("/api/v1/users/"+id, accessToken);
    }

    private UUID userId(TokenResponse tokens) throws Exception {
        var me=get("/api/v1/users/me", tokens.accessToken());
        assertEquals(HttpStatus.OK, me.getStatusCode(), me.getBody());
        return UUID.fromString(json.readTree(me.getBody()).get("id").asText());
    }

    private TokenResponse tokenFrom(ResponseEntity<String> res) {
        try { return json.readValue(res.getBody(), TokenResponse.class); }
        catch (Exception e) { throw new AssertionError("Not a token response: "+res.getBody(), e); }
    }

    private boolean isRevoked(String rawRefreshToken) {
        return refreshTokens.findByTokenHash(refreshTokenService.hash(rawRefreshToken)).orElseThrow().isRevoked();
    }
}
