# User Service API: cURL and smoke tests

Base URL: `http://localhost:8080`

## Run the automated PowerShell smoke test

From the `user-service` directory, run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\test-api.ps1"
```

To test a different server:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\test-api.ps1" -BaseUrl "http://localhost:8080"
```

The script checks the health, OpenAPI, and Swagger UI routes; exercises all six
application API routes; and checks authentication, refresh-token rotation,
logout, rejected credentials, invalid registration data, and duplicate
registration. When the `user-service-redis` container is reachable through
`docker`, it also verifies the Redis profile cache, the rate-limit headers and
the failed-login lockout (`429` + `Retry-After`), and clears rate-limit buckets
before running so reruns are not throttled. Pass `-SkipRedisChecks` to skip this. It registers a uniquely named disposable user on each run. The
service has no user-delete endpoint, so that account remains in the local
database.

## cURL examples

These examples use Bash syntax and `jq` to pass values between requests. The
request bodies match the service DTOs. Run them in order to use the same test
account throughout.

```bash
BASE_URL="http://localhost:8080"
EMAIL="curl-test-$(date +%s)@example.com"
PASSWORD="StrongPassword123!"
```

### Health

```bash
curl -i "$BASE_URL/actuator/health"
```

Expected: `200 OK` and a JSON health status of `UP`.

### OpenAPI specification and Swagger UI

```bash
curl -i "$BASE_URL/v3/api-docs"
curl -i "$BASE_URL/swagger-ui/index.html"
```

Expected: `200 OK` for both routes.

### Register

```bash
REGISTER_RESPONSE=$(
  curl -sS -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"firstName\":\"Curl\",\"lastName\":\"Test\"}"
)
printf '%s\n' "$REGISTER_RESPONSE" | jq .
ACCESS_TOKEN=$(printf '%s' "$REGISTER_RESPONSE" | jq -r .accessToken)
REFRESH_TOKEN=$(printf '%s' "$REGISTER_RESPONSE" | jq -r .refreshToken)
```

Expected: `201 Created`; the response contains `accessToken`, `refreshToken`,
`expiresInSeconds`, and `tokenType`.

Invalid input (for example, an invalid email or password shorter than 12
characters) returns `400 Bad Request`. Registering the same email again returns
`409 Conflict`.

### Login

```bash
LOGIN_RESPONSE=$(
  curl -sS -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"
)
printf '%s\n' "$LOGIN_RESPONSE" | jq .
```

Expected: `200 OK` and a token response. A wrong password returns `401`.

### Get the current user

```bash
ME_RESPONSE=$(
  curl -sS "$BASE_URL/api/v1/users/me" \
    -H "Authorization: Bearer $ACCESS_TOKEN"
)
printf '%s\n' "$ME_RESPONSE" | jq .
USER_ID=$(printf '%s' "$ME_RESPONSE" | jq -r .id)
```

Expected: `200 OK`; the response contains the registered email, user ID,
roles, and permissions. Without a bearer token, this route returns `401`.

### Get a user by ID

```bash
curl -i "$BASE_URL/api/v1/users/$USER_ID" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Expected: `200 OK` for a token with `USER_READ` permission.

### Refresh tokens

```bash
REFRESH_RESPONSE=$(
  curl -sS -X POST "$BASE_URL/api/v1/auth/refresh" \
    -H "Content-Type: application/json" \
    -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}"
)
printf '%s\n' "$REFRESH_RESPONSE" | jq .
ACCESS_TOKEN=$(printf '%s' "$REFRESH_RESPONSE" | jq -r .accessToken)
REFRESH_TOKEN=$(printf '%s' "$REFRESH_RESPONSE" | jq -r .refreshToken)
```

Expected: `200 OK` with a new access token and a rotated refresh token. Reusing
the previous refresh token returns `401`.

### Logout

```bash
curl -i -X POST "$BASE_URL/api/v1/auth/logout" \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}"
```

Expected: `204 No Content`. Refreshing with the logged-out token returns `401`.

### Rate limiting

```bash
for i in 1 2 3 4 5 6; do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"victim@example.com","password":"WrongPassword123!"}'
done
```

Expected: five `401` responses, then `429 Too Many Requests` with a
`Retry-After` header. Successful responses carry `X-RateLimit-Limit` and
`X-RateLimit-Remaining` headers.

## Routes covered

| Method | Path | Expected success |
| --- | --- | --- |
| GET | `/actuator/health` | 200 |
| GET | `/v3/api-docs` | 200 |
| GET | `/swagger-ui/index.html` | 200 |
| POST | `/api/v1/auth/register` | 201 |
| POST | `/api/v1/auth/login` | 200 |
| POST | `/api/v1/auth/refresh` | 200 |
| POST | `/api/v1/auth/logout` | 204 |
| GET | `/api/v1/users/me` | 200 with bearer token |
| GET | `/api/v1/users/{id}` | 200 with bearer token and `USER_READ` |
