# User Service — Authentication & Authorization

## Stack
Java 21, Spring Boot 3.5, Spring Security, PostgreSQL, Redis, Flyway, JWT/RS256, Argon2.

## Run locally

1. Generate keys:
   `openssl genrsa -out keys/private.pem 3072`
   `openssl rsa -in keys/private.pem -pubout -out keys/public.pem`

2. Start everything (Postgres, Redis, app):
   `docker compose --profile app up -d --build`

3. Test:
   `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-api.ps1`

See [RUNNING.md](./RUNNING.md) for the full guide: running from Maven/IDE, all test suites,
Redis inspection, outage drill, configuration and rate-limit policies, and troubleshooting.

## Redis usage

- **Caching:** user profiles (`GET /users/me`, `GET /users/{id}`) are cached as JSON with a TTL.
- **Rate limiting:** distributed token buckets (atomic Lua script) on all auth and user endpoints;
  failed logins are limited per account+IP and per account.
- Both degrade gracefully if Redis is down (DB reads, in-memory limits).

## APIs

POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
GET  /api/v1/users/me
GET  /api/v1/users/{id}   (own profile, or any user with USER_READ (ADMIN); another user's ID returns 403)

Swagger UI is available at `http://localhost:8080/swagger-ui/index.html`.
See [API-CURL-AND-TESTING.md](./API-CURL-AND-TESTING.md) for cURL examples and
the PowerShell API smoke-test command.

## Example register

{
  "email": "sumit@example.com",
  "password": "StrongPassword123!",
  "firstName": "Sumit",
  "lastName": "Gaurav"
}

Access token is short-lived. Refresh token is opaque, hashed at rest and rotated on use.
Replaying an already-rotated refresh token is treated as theft: every refresh token of that user is revoked.

## Production hardening

- Put private key in Vault/KMS/HSM rather than a filesystem.
- Put access/refresh tokens behind HTTPS only.
- Add gateway-level rate limiting in front of the in-service limits.
- Add email verification and password reset with one-time hashed tokens.
- Add audit events for logins and lockouts.
- Set `CORS_ALLOWED_ORIGINS` to your front-end origins (empty by default: no cross-origin access).
- Add a circuit breaker around Redis so an outage skips the timeout instead of waiting for it.
- Consider external OIDC authorization server (Keycloak/Okta/Auth0/etc.) at larger scale.
