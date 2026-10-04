# Running and testing user-service

End-to-end guide: from a fresh clone to a running, tested service. Commands are for **Windows PowerShell**
unless marked otherwise, and are run from the `user-service` folder.

Diagrams use [Mermaid](https://mermaid.js.org/), which renders natively on GitHub. In VS Code, install the
*Markdown Preview Mermaid Support* extension and open the preview (`Ctrl+Shift+V`).

**Contents**

- [Architecture: HLD](#architecture--high-level-design-hld): system context, deployment, request pipeline, design decisions
- [Architecture: LLD](#architecture--low-level-design-lld): components, classes, data model, sequences, algorithms, Redis keys
- [1. Prerequisites](#1-prerequisites) · [2. Keys](#2-one-time-setup-jwt-signing-keys) · [3. Run](#3-run-the-application) ·
  [4. Testing](#4-testing) · [5. Configuration](#5-configuration-reference) · [6. Deploying](#6-deploying-behind-a-load-balancer--kubernetes) ·
  [7. Troubleshooting](#7-troubleshooting) · [8. Clean up](#8-stop-and-clean-up)

---

## Architecture — High-Level Design (HLD)

### H1. System context

Who talks to the service, and what it depends on.

```mermaid
flowchart LR
    client["Client apps<br/>web / mobile / backend"]
    lb["Load balancer / Ingress<br/>TLS termination<br/>sets X-Forwarded-For"]
    subgraph svc["user-service: stateless, horizontally scaled"]
        direction TB
        i1["Instance 1"]
        i2["Instance 2"]
        i3["Instance N"]
    end
    pg[("PostgreSQL<br/>source of truth<br/>users, roles, permissions,<br/>refresh tokens")]
    redis[("Redis<br/>profile cache<br/>rate-limit buckets")]
    secrets["Secret manager<br/>Vault / KMS<br/>RSA private key"]
    rs["Other microservices<br/>resource servers"]

    client -->|"HTTPS: register, login,<br/>refresh, /users"| lb
    lb --> i1 & i2 & i3
    svc -->|"JPA + Flyway"| pg
    svc -->|"Lettuce, 500ms timeout"| redis
    secrets -.->|"private key at startup"| svc
    client -->|"Bearer access token"| rs
    rs -.->|"verify RS256 signature<br/>with public key only,<br/>no call to user-service"| rs
```

### H2. Local deployment (docker compose)

```mermaid
flowchart LR
    dev["Developer machine<br/>browser / curl / test-api.ps1"]
    subgraph compose["docker compose"]
        app["user-service-app<br/>:8080<br/>profile: app"]
        pg[("user-service-postgres<br/>postgres:17<br/>:5432")]
        redis[("user-service-redis<br/>redis:7.4-alpine<br/>:6379")]
        vpg[["volume postgres_data"]]
        vredis[["volume redis_data"]]
        keys[["./keys mounted read-only"]]
    end
    ide["Option B: app in IDE / mvn<br/>localhost:8080"]

    dev -->|":8080"| app
    app -->|"healthy before start"| pg
    app -->|"healthy before start"| redis
    pg --- vpg
    redis --- vredis
    keys --- app
    ide -.->|"localhost:5432"| pg
    ide -.->|"localhost:6379"| redis
```

### H3. Request pipeline

Every HTTP request passes through these layers in order. Cheap rejections happen before expensive work.

```mermaid
flowchart LR
    req(["HTTP request"]) --> valve["Tomcat RemoteIpValve<br/>resolve real client IP<br/>trust XFF only from private proxies"]
    valve --> sec["Spring Security filter chain<br/>public: /auth/**, health, docs<br/>others: verify JWT RS256 + issuer + expiry"]
    sec -->|"invalid / missing JWT"| e401(["401"])
    sec --> disp["DispatcherServlet"]
    disp --> rli["RateLimitInterceptor<br/>@RateLimit policies<br/>before body is read"]
    rli -->|"bucket empty"| e429(["429 + Retry-After"])
    rli --> ctrl["Controller<br/>@Valid DTOs<br/>@PreAuthorize"]
    ctrl --> svcs["Service layer<br/>@Transactional, @Cacheable"]
    svcs --> data[("Postgres / Redis")]
    ctrl -.->|exceptions| geh["GlobalExceptionHandler<br/>uniform JSON error body"]
    rli -. "RateLimitExceededException" .-> geh
```

### H4. Key design decisions

| Concern | Decision | Why |
|---|---|---|
| Session model | Stateless **JWT access tokens** (RS256, 15 min) | Any instance or downstream service verifies with the public key only, with no DB or network hop |
| Long-lived sessions | Opaque **refresh tokens** (30 days), stored as SHA-256 hashes, rotated on every use; replaying a rotated token revokes all of the user's tokens | A DB leak exposes no usable tokens; rotation plus reuse detection limits how long a stolen token is useful |
| Passwords | **Argon2** | Memory-hard, so GPU cracking is resistant |
| Authorization | RBAC: user → roles → permissions, embedded as JWT claims | No per-request permission lookup |
| Source of truth | **PostgreSQL**, schema via Flyway migrations | Transactions, constraints, versioned schema |
| Redis role | Optimisation and protection only, **never** the source of truth | The app keeps working when Redis is down |
| Caching | Cache-aside on user profiles, typed JSON, TTL, versioned keys | Removes a 3-table join from the hottest read path |
| Rate limiting | Distributed token bucket, atomic Lua script, per-policy fallback | Consistent limits across instances; brute-force protection without account-lockout DoS |
| Scaling | App instances are stateless | Scale horizontally behind the load balancer |
| Observability | Actuator health groups, Micrometer counters (`rate_limiter.requests`, cache stats) | Readiness stays green during a Redis outage; overall health reports it |

---

## Architecture — Low-Level Design (LLD)

### L1. Component / package structure

```mermaid
flowchart TB
    subgraph auth["auth"]
        AC["AuthController<br/>/api/v1/auth/*"]
        AS["AuthService"]
        JS["JwtService"]
        RTS["RefreshTokenService"]
        SUDS["SecurityUserDetailsService"]
        CJ["RefreshTokenCleanupJob<br/>hourly @Scheduled"]
        RTR["RefreshTokenRepository"]
    end
    subgraph user["user"]
        UC["UserController<br/>/api/v1/users/*"]
        US["UserService<br/>@Cacheable users"]
        UR["UserRepository"]
        RR["RoleRepository"]
    end
    subgraph ratelimit["ratelimit"]
        RLI["RateLimitInterceptor"]
        RRL["RedisRateLimiter"]
        LTB["LocalTokenBuckets"]
        LUA[/"token_bucket.lua"/]
    end
    subgraph config["config"]
        SC["SecurityConfig"]
        JC["JwtConfig<br/>encoder / decoder"]
        RC["RedisConfig<br/>cache manager"]
        RLC["RateLimitConfig"]
    end
    subgraph common["common"]
        GEH["GlobalExceptionHandler"]
        AE["ApiException"]
    end

    AC --> AS
    AS --> JS & RTS & RRL & UR & RR
    AS -->|"via AuthenticationManager"| SUDS
    SUDS --> UR
    RTS --> RTR
    CJ --> RTR
    UC --> US --> UR
    RLC -->|registers| RLI
    RLI --> RRL
    RRL --> LUA
    RRL -->|Redis down| LTB
    JC --> JS
    RC -.->|configures cache for| US
```

### L2. Class diagram: rate limiting

```mermaid
classDiagram
    direction LR
    class RateLimit {
        <<annotation>>
        +String policy
        +KeyType key
    }
    class KeyType {
        <<enumeration>>
        IP
        USER
    }
    class RateLimitInterceptor {
        -RedisRateLimiter limiter
        +preHandle(req, res, handler) boolean
        +limitsFor(HandlerMethod) Set~RateLimit~
        -identity(KeyType, req) String
    }
    class RedisRateLimiter {
        -StringRedisTemplate redis
        -RateLimitProperties props
        -MeterRegistry meters
        -LocalTokenBuckets local
        +consume(policy, id) RateLimitDecision
        +check(policy, id) RateLimitDecision
        +recordFailure(policy, id) void
        +reset(policy, id) void
        -evaluate(policy, id, cost) RateLimitDecision
        -hash(id) String
    }
    class LocalTokenBuckets {
        -Cache~String,Bucket~ buckets
        +evaluate(key, policy, cost) RateLimitDecision
        +reset(key) void
    }
    class RateLimitProperties {
        <<record>>
        +boolean enabled
        +String keyPrefix
        +Map~String,Policy~ policies
        +policy(name) Policy
    }
    class Policy {
        <<record>>
        +long capacity
        +long refillTokens
        +Duration refillPeriod
        +FailureMode onRedisFailure
    }
    class FailureMode {
        <<enumeration>>
        ALLOW
        LOCAL
        DENY
    }
    class RateLimitDecision {
        <<record>>
        +boolean allowed
        +long limit
        +long remaining
        +long retryAfterMillis
        +enforced() boolean
        +retryAfterSeconds() long
    }
    class RateLimitExceededException {
        +getDecision() RateLimitDecision
    }
    class ApiException {
        +getStatus() HttpStatus
    }
    class RateLimitConfig {
        +addInterceptors(registry)
        +rateLimitPolicyValidator() SmartInitializingSingleton
    }

    RateLimit --> KeyType
    RateLimitInterceptor ..> RateLimit : reads
    RateLimitInterceptor --> RedisRateLimiter
    RedisRateLimiter --> RateLimitProperties
    RedisRateLimiter --> LocalTokenBuckets : fallback
    RedisRateLimiter ..> RateLimitDecision : returns
    RedisRateLimiter ..> RateLimitExceededException : throws
    RateLimitProperties *-- Policy
    Policy --> FailureMode
    RateLimitExceededException --|> ApiException
    RateLimitExceededException --> RateLimitDecision
    RateLimitConfig ..> RateLimitInterceptor : registers
```

### L3. Class diagram: caching

```mermaid
classDiagram
    direction LR
    class CachingConfigurer {
        <<interface>>
        +errorHandler() CacheErrorHandler
    }
    class RedisConfig {
        <<configuration>>
        +redisCacheCustomizer(props, mapper) RedisCacheManagerBuilderCustomizer
        +errorHandler() CacheErrorHandler
    }
    class FailOpenCacheErrorHandler {
        +handleCacheGetError() void
        +handleCachePutError() void
        +handleCacheEvictError() void
    }
    class AppCacheProperties {
        <<record>>
        +String keyPrefix
        +Duration userTtl
    }
    class CacheNames {
        +String USERS$
    }
    class UserService {
        +get(UUID id) UserResponse
    }
    class UserResponse {
        <<record>>
        +UUID id
        +String email
        +String firstName
        +String lastName
        +boolean enabled
        +boolean emailVerified
        +Set~String~ roles
        +Set~String~ permissions
    }

    RedisConfig ..|> CachingConfigurer
    RedisConfig --> AppCacheProperties
    RedisConfig *-- FailOpenCacheErrorHandler
    RedisConfig ..> UserResponse : Jackson2JsonRedisSerializer
    UserService ..> CacheNames : @Cacheable(USERS)
    UserService ..> UserResponse : returns
```

### L4. Data model (PostgreSQL)

```mermaid
erDiagram
    USERS ||--o{ USER_ROLES : has
    ROLES ||--o{ USER_ROLES : "assigned to"
    ROLES ||--o{ ROLE_PERMISSIONS : grants
    PERMISSIONS ||--o{ ROLE_PERMISSIONS : "granted by"
    USERS ||--o{ REFRESH_TOKENS : owns

    USERS {
        uuid id PK
        varchar email UK "lower-cased, max 320"
        varchar password_hash "Argon2"
        varchar first_name
        varchar last_name
        boolean enabled
        boolean email_verified
        timestamptz created_at
        timestamptz updated_at
    }
    ROLES {
        uuid id PK
        varchar name UK "USER, ADMIN"
    }
    PERMISSIONS {
        uuid id PK
        varchar name UK "USER_READ, USER_CREATE, ..."
    }
    USER_ROLES {
        uuid user_id PK, FK
        uuid role_id PK, FK
    }
    ROLE_PERMISSIONS {
        uuid role_id PK, FK
        uuid permission_id PK, FK
    }
    REFRESH_TOKENS {
        uuid id PK
        uuid user_id FK
        varchar token_hash UK "SHA-256 of raw token"
        timestamptz expires_at "indexed"
        boolean revoked
        varchar replaced_by_hash "rotation chain, reuse detection"
        timestamptz created_at
    }
```

Seed data (Flyway `V2`, adjusted by `V3`): `ADMIN` → all permissions; `USER` → no permissions (`V3` removed
`USER_READ`, so a regular user can read only their own profile via `/users/me` or `/users/{own id}`). New
registrations get `USER`. `V3` also drops the unused `users.failed_login_attempts` column (failed logins are
throttled by the rate limiter).

### L5. Sequence: register

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant I as RateLimitInterceptor
    participant L as RedisRateLimiter
    participant R as Redis
    participant A as AuthService
    participant DB as PostgreSQL
    participant J as JwtService

    C->>I: POST /auth/register {email, password, names}
    I->>L: consume(register-ip, ip)
    L->>R: EVALSHA token_bucket (cost 1)
    alt bucket empty
        L-->>C: 429 + Retry-After
    end
    I->>A: register(req) after @Valid passes, else 400
    A->>DB: existsByEmailIgnoreCase
    alt email taken
        A-->>C: 409 Conflict
    end
    A->>A: Argon2 hash password
    A->>DB: INSERT user + user_roles(USER)
    A->>J: createAccessToken(user)
    J-->>A: JWT RS256 with sub, email, roles, permissions
    A->>A: random 64-byte refresh token
    A->>DB: INSERT refresh_tokens(sha256(token), expires +30d)
    A-->>C: 201 {accessToken, refreshToken, expiresInSeconds, tokenType}
```

### L6. Sequence: login with brute-force protection

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant I as RateLimitInterceptor
    participant L as RedisRateLimiter
    participant A as AuthService
    participant M as AuthenticationManager
    participant DB as PostgreSQL

    C->>I: POST /auth/login {email, password}
    I->>L: consume(login-ip, ip)
    Note over L: every attempt counts, 20/min per IP
    I->>A: login(req, clientIp)
    A->>L: check(login-email-ip, email+ip)
    A->>L: check(login-email, email)
    Note over L: cost 0, read-only, failures only
    alt either bucket empty
        L-->>C: 429 + Retry-After
    end
    A->>M: authenticate(email, password)
    M->>DB: load user with roles + permissions
    M->>M: Argon2 verify, check enabled
    alt bad credentials
        A->>L: recordFailure(login-email-ip)
        A->>L: recordFailure(login-email)
        A-->>C: 401 Invalid email or password
    else success
        A->>L: reset(login-email-ip)
        Note over A,L: per-account bucket is NOT reset,<br/>so attackers get no fresh budget
        A->>A: issue access + refresh tokens
        A-->>C: 200 tokens + X-RateLimit headers
    end
```

### L7. Sequence: GET /users/me (JWT + cache-aside)

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant S as Security filter
    participant I as RateLimitInterceptor
    participant U as UserService proxy
    participant R as Redis
    participant DB as PostgreSQL

    C->>S: GET /users/me + Bearer JWT
    S->>S: verify RS256 signature, issuer, expiry
    alt invalid
        S-->>C: 401
    end
    S->>I: authenticated request
    I->>I: consume(user-api, user:sub)
    I->>U: get(sub)
    U->>R: GET user-service:v1:users::{id}
    alt cache hit
        R-->>U: JSON
        U-->>C: 200 UserResponse
    else cache miss
        U->>DB: select user join roles join permissions
        DB-->>U: user row
        U->>R: SET key JSON, TTL 10m
        U-->>C: 200 UserResponse
    else Redis error or timeout
        Note over U: FailOpenCacheErrorHandler logs,<br/>treated as a miss, read from DB
        U->>DB: select user join roles join permissions
        U-->>C: 200 UserResponse
    end
```

### L8. Sequence: refresh-token rotation and logout

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant A as AuthService
    participant T as RefreshTokenService
    participant DB as PostgreSQL

    C->>A: POST /auth/refresh {refreshToken}
    Note over C,A: token-ip rate limit applied first
    A->>T: rotate(raw)
    T->>DB: SELECT ... WHERE token_hash = sha256(raw) FOR UPDATE
    Note over T,DB: row lock: a concurrent refresh or logout<br/>of the same token waits, then sees revoked = true
    alt not found or expired
        T-->>C: 401
    else revoked and replaced_by_hash set (reuse of a rotated token)
        T->>DB: UPDATE refresh_tokens SET revoked = true<br/>WHERE user_id = ? AND NOT revoked
        Note over T: WARN log: user id + count.<br/>Committed despite the 401 (noRollbackFor)
        T-->>C: 401
    else revoked by logout (replaced_by_hash null)
        T-->>C: 401, other sessions untouched
    end
    T->>DB: mark old revoked, replaced_by_hash = sha256(new)
    T->>DB: INSERT new refresh token row, expires +30d
    A-->>C: 200 new access token + new refresh token

    C->>A: POST /auth/logout {refreshToken}
    A->>T: revoke(raw)
    T->>DB: SELECT ... FOR UPDATE, set revoked = true if found
    A-->>C: 204

    Note over T,DB: RefreshTokenCleanupJob, hourly:<br/>DELETE expired tokens only (revoked rows are<br/>kept until expiry as evidence for reuse detection)
```

### L9. Algorithm: token bucket (`token_bucket.lua`, atomic in Redis)

```mermaid
flowchart TD
    start(["EVALSHA key, capacity, refillTokens, periodMs, cost"]) --> now["now = Redis TIME in ms<br/>rate = refillTokens / periodMs"]
    now --> load["HMGET key tokens, ts"]
    load --> exists{"key exists?"}
    exists -->|no| full["tokens = capacity<br/>ts = now"]
    exists -->|yes| keep["use stored values"]
    full --> refill
    keep --> refill["tokens = min(capacity, tokens + (now - ts) * rate)"]
    refill --> req["required = max(cost, 1)"]
    req --> enough{"tokens >= required?"}
    enough -->|yes| take["tokens -= cost<br/>allowed = 1"]
    enough -->|no| deny["allowed = 0<br/>retryMs = (required - tokens) / rate"]
    take --> save
    deny --> save["HSET key tokens, ts=now<br/>PEXPIRE key = time until full"]
    save --> ret(["return allowed, floor(tokens), retryMs"])
```

`cost = 1` consumes a token (`consume`, `recordFailure`). `cost = 0` only checks that at least one token is left
(`check`). Using Redis `TIME` keeps buckets consistent even when app servers' clocks differ.

### L10. Redis failure handling

```mermaid
flowchart TD
    call["Redis call<br/>timeout 500ms"] --> ok{"success?"}
    ok -->|yes| normal(["normal path"])
    ok -->|"no: connection refused / timeout"| which{"which feature?"}
    which -->|cache| miss["FailOpenCacheErrorHandler<br/>log WARN, treat as miss"] --> db(["read from PostgreSQL"])
    which -->|rate limit| mode{"policy on-redis-failure"}
    mode -->|local, default| local(["LocalTokenBuckets<br/>same limits per instance<br/>Caffeine, max 100k buckets"])
    mode -->|allow| allow(["let request through"])
    mode -->|deny| deny(["503 Service Unavailable"])
    which --> metric["counter rate_limiter.requests<br/>backend=local / allow / deny<br/>WARN log at most once per 30s"]
```

### L11. Redis key design

| Purpose | Key pattern | Type | TTL | Example value |
|---|---|---|---|---|
| User profile cache | `user-service:v1:users::{userId}` | string (JSON) | `CACHE_USER_TTL` (10 min) | `{"id":"…","email":"…","roles":["USER"],…}` |
| Rate-limit bucket | `user-service:rl:{policy}:{sha256(identity)[0..32]}` | hash | time until the bucket is full again | `tokens=4.7, ts=1759557600123` |

Identity values hashed into rate-limit keys: `ip:{clientIp}`, `user:{jwtSub}`, `{email}`, `{email}|{ip}`.
The `v1` segment lets a changed DTO shape roll out without reading old entries; the `user-service:` prefix
keeps keys separate when Redis is shared with other services.

---

## 1. Prerequisites

| Tool | Version | Check | Needed for |
|---|---|---|---|
| Docker Desktop | any recent | `docker info` | Postgres, Redis, and the app container |
| JDK | **21 or newer** | `java -version` | only for running via Maven/IDE and unit tests |
| Maven | 3.9+ | `mvn -v` | same as above |
| OpenSSL | any | `openssl version` | generating the JWT key pair (ships with Git for Windows) |

> **Java version gotcha:** `mvn -v` shows which JDK Maven uses. If it says 17, point it at a newer JDK for the
> current terminal: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-23"`

Start **Docker Desktop** and wait until it says *Engine running* before continuing.

---

## 2. One-time setup: JWT signing keys

The service signs access tokens with an RSA private key. It is git-ignored and must be generated locally.
Run in **Git Bash** (or prefix `openssl` with `& "C:\Program Files\Git\usr\bin\openssl.exe"` in PowerShell):

```bash
openssl genrsa -out keys/private.pem 3072
openssl rsa -in keys/private.pem -pubout -out keys/public.pem
```

Never commit `keys/private.pem`. In production, load it from a secret manager (Vault/KMS).

---

## 3. Run the application

Pick **one** option.

### Option A: everything in Docker (recommended, no local Java needed)

```powershell
docker compose --profile app up -d --build
```

The first build downloads Maven dependencies and takes a few minutes; later builds are cached.
Follow startup logs with `docker logs -f user-service-app` and wait for `Started UserServiceApplication`.

After changing code, rebuild just the app: `docker compose --profile app up -d --build app`

### Option B: infrastructure in Docker, app from Maven or your IDE (best for debugging)

```powershell
docker compose up -d          # only postgres + redis
mvn spring-boot:run           # or run UserServiceApplication from IntelliJ
```

Don't run both options at once: they both want port 8080.

### Check it's up

| URL | Expect |
|---|---|
| http://localhost:8080/actuator/health | `{"status":"UP"}` |
| http://localhost:8080/swagger-ui/index.html | interactive API docs |

Flyway creates the schema and seeds the `USER`/`ADMIN` roles automatically on first start.

---

## 4. Testing

### 4.1 Unit and Redis tests

```powershell
mvn test
```

34 tests: refresh-token rotation and reuse detection, error handling (403/429), cache serialization, rate-limit interceptor, Redis-outage fallbacks, and the Lua token bucket
against a real Redis (via Testcontainers). `AuthFlowIntegrationTest` starts the whole app over HTTP against
real Postgres 17 + Redis containers (Flyway V1–V3) and covers `/users/{id}` access control (own 200, other 403,
ADMIN 200), reuse detection committed despite the 401, logout, and two concurrent refreshes of the same token
(exactly one succeeds, which proves the row lock). The container tests are **skipped automatically** if
Docker isn't running, so make sure it is.

### 4.2 API smoke test (against the running app)

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-api.ps1
```

Expected last line: `Summary: 38 passed, 0 failed, 38 total.` It covers:

- health, OpenAPI, Swagger UI
- register (valid / invalid 400 / duplicate 409), login, wrong password 401
- `/users/me`, `/users/{id}` (own profile 200, another user's profile 403), missing token 401
- refresh-token rotation (R1 → R2 → R3), logout of R3 and R3 rejected, then replaying R1 → 401 and the
  login session's refresh token is revoked too (reuse detection)
- **Redis cache:** profile is stored under `user-service:v1:users::<id>` with a TTL
- **Rate limiting:** headers present; 5 failed logins → 6th gets `429` + `Retry-After`;
  the blocked account doesn't affect other users on the same IP

The script clears rate-limit buckets in the `user-service-redis` container before each run, so you can
rerun it freely. Use `-SkipRedisChecks` when testing a server that isn't your local Docker stack, and
`-BaseUrl http://host:port` to target another server.

### 4.3 Manual testing

- **Swagger UI:** open http://localhost:8080/swagger-ui/index.html, call `register` or `login`, copy the
  `accessToken`, click **Authorize**, and paste it in to call the `/users` endpoints.
- **cURL:** step-by-step examples are in [API-CURL-AND-TESTING.md](API-CURL-AND-TESTING.md).

### 4.4 Look inside Redis

```powershell
docker exec -it user-service-redis redis-cli
```

```
SCAN 0 MATCH user-service:* COUNT 100     # all keys this service owns
GET user-service:v1:users::<user-id>      # cached profile JSON
TTL user-service:v1:users::<user-id>      # seconds until it expires (default 600)
HGETALL user-service:rl:login-ip:<hash>   # a rate-limit bucket: tokens left + last refill time
```

Rate-limit keys contain a SHA-256 hash, never the raw IP or email.

### 4.5 Redis outage drill (resilience)

Redis is an optimisation, not a hard dependency. Verify it:

```powershell
docker stop user-service-redis
```

Then, while Redis is down:

| Action | Expected |
|---|---|
| register / login / `/users/me` | still work (cache misses go to Postgres) |
| 6 wrong-password logins for one email | still `401 ×5` then `429` (in-memory fallback limiter) |
| `/actuator/health/readiness` | `200`: the instance keeps receiving traffic |
| `/actuator/health` | `503 DOWN`, with Redis shown as down (for dashboards/alerts) |
| app logs | `Rate limiter cannot reach Redis…` (at most once per 30 s) and `Cache GET failed…` |

Bring it back with `docker start user-service-redis`; the app reconnects automatically.

> Requests are slower during an outage: each Redis call waits for the 500 ms timeout before falling back.

---

## 5. Configuration reference

All settings are in `src/main/resources/application.yml` and can be overridden with environment variables.

| Env var | Default | Purpose |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | local Postgres | database |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / none | Redis |
| `REDIS_TIMEOUT` | `500ms` | Redis command timeout (keep short: fallbacks only start after it) |
| `CACHE_USER_TTL` | `10m` | how long a user profile stays cached |
| `CACHE_KEY_PREFIX` | `user-service:v1:` | bump to `v2` when `UserResponse` changes shape |
| `RATE_LIMIT_ENABLED` | `true` | master switch for rate limiting |
| `FORWARD_HEADERS_STRATEGY` | `native` | how the real client IP is resolved behind proxies |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | private ranges | regex of proxy IPs trusted to send `X-Forwarded-For` |
| `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` | `keys/*.pem` | file path or PEM content |
| `JWT_ACCESS_SECONDS` / `JWT_REFRESH_DAYS` | `900` / `30` | token lifetimes |
| `CORS_ALLOWED_ORIGINS` | empty | comma-separated browser origins allowed to call `/api/**` (empty = no cross-origin access) |

### Rate-limit policies

| Policy | Limit | Counted per | Applies to |
|---|---|---|---|
| `login-ip` | 20 / min | IP | every `/auth/login` request |
| `login-email-ip` | 5 / 15 min | account + IP | **failed** logins only; reset on success |
| `login-email` | 50 / hour | account | **failed** logins only (catches attacks spread over many IPs) |
| `register-ip` | 5 / hour | IP | `/auth/register` |
| `token-ip` | 30 / min | IP | `/auth/refresh`, `/auth/logout` |
| `user-api` | 100 / min | user (JWT) | all `/users/**` endpoints |

Each policy has `on-redis-failure`: `local` (default: in-memory limits per instance), `allow`, or `deny` (503).
To limit a new endpoint, add `@RateLimit(policy = "...", key = IP | USER)` to the controller method and define the
policy in `application.yml`. The app refuses to start if a referenced policy is missing from config.

---

## 6. Deploying behind a load balancer / Kubernetes

- **Probes:** use `/actuator/health/liveness` and `/actuator/health/readiness`. Don't use `/actuator/health` as a
  load-balancer check: it reports `DOWN` during a Redis outage and would pull every instance out of rotation.
- **Client IP:** with the default `native` strategy, `X-Forwarded-For` is trusted only from private-network
  proxies. If your load balancer has public IPs, set `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` to a regex matching
  them; otherwise all traffic will look like it comes from the load balancer and share one rate-limit bucket.
- **Limits are cluster-wide** while Redis is up; during an outage each instance enforces them separately.

---

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| `release version 21 not supported` | Maven is on an old JDK; set `JAVA_HOME` (see prerequisites) |
| app exits with `NoSuchFileException: keys/private.pem` | generate keys (step 2) |
| `open //./pipe/dockerDesktopLinuxEngine` | Docker Desktop isn't running |
| `Port 8080 / 5432 / 6379 already in use` | stop the other process, or the other run option (section 3) |
| `429 Too Many Requests` while testing manually | wait for `Retry-After` seconds, or clear buckets: `docker exec user-service-redis sh -c "redis-cli --scan --pattern 'user-service:rl:*' \| xargs -r redis-cli del"` |
| Redis tests skipped in `mvn test` | start Docker Desktop |

---

## 8. Stop and clean up

```powershell
docker compose --profile app down        # stop containers, keep data
docker compose --profile app down -v     # also delete the Postgres and Redis data volumes
```
