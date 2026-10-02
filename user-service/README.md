# User Service — Authentication & Authorization

## Stack
Java 21, Spring Boot 3.5, Spring Security, PostgreSQL, Redis, Flyway, JWT/RS256, Argon2.

## Run locally

1. Generate keys:
   `openssl genrsa -out keys/private.pem 3072`
   `openssl rsa -in keys/private.pem -pubout -out keys/public.pem`

2. Start infrastructure:
   `docker compose up -d`

3. Run:
   `mvn spring-boot:run`

## APIs

POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
GET  /api/v1/users/me
GET  /api/v1/users/{id}   (USER_READ)

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

## Production hardening

- Put private key in Vault/KMS/HSM rather than a filesystem.
- Put access/refresh tokens behind HTTPS only.
- Add gateway-level rate limiting and login-specific throttling.
- Add email verification and password reset with one-time hashed tokens.
- Add account lockout/backoff and audit events.
- Add CORS allowlist rather than permissive defaults.
- Add Redis-backed distributed rate limiting/session metadata.
- Consider external OIDC authorization server (Keycloak/Okta/Auth0/etc.) at larger scale.
