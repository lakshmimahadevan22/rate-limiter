# Project Plan: Distributed API Gateway & Rate Limiting System

Here's a phase-by-phase build order. Each phase produces something runnable/testable before you move to the next, so you always have a working checkpoint.

---

## Phase 0 — Project Setup & Scaffolding

- Init Git repo, `.gitignore`, README skeleton
- Maven multi-module layout: `api-gateway` (the gateway) + `demo-backend` (a trivial "backend service" to route to)
- `pom.xml` with core dependencies: Spring Boot, Spring Cloud Gateway, Spring Security, Resilience4j, Redis, R2DBC/Postgres, JJWT, Lombok, Testcontainers
- Base `application.yml` (ports, empty route table)
- **Checkpoint:** `mvn clean install` builds; app boots with no routes configured.

---

## Phase 1 — Core Gateway & Routing

- Configure Spring Cloud Gateway routes (predicates: Path, Method, Header)
- Stand up `demo-backend`: a minimal Spring Boot REST service (a couple of endpoints) to prove routing works
- Global filters: request logging, correlation/request-ID header injection
- **Checkpoint:** Client → Gateway → demo-backend round trip works over HTTP, no auth/rate-limit yet.

---

## Phase 2 — Authentication (JWT)

- `JwtUtil`: token generation, parsing, signature validation (HS256/RS256)
- `AuthController`: `/auth/login` or `/auth/token` issuing JWTs (hardcoded or DB-backed users)
- Spring Security filter chain integrated into the reactive gateway (`SecurityWebFilterChain`)
- Custom `GatewayFilter`/`WebFilter` that validates the `Authorization: Bearer` header before requests reach routing
- **Checkpoint:** Unauthenticated requests to protected routes get 401; valid JWT passes through.

---

## Phase 3 — Rate Limiting (Redis + Lua)

- Token-bucket Lua script (atomic, race-safe across gateway instances) — *already drafted*
- `RateLimitingFilter`: extracts client identity (API key or JWT subject), calls Redis via `ReactiveRedisTemplate.execute(RedisScript)`
- Config-driven limits (`RateLimitProperties`) — per-client override support (different tiers)
- Return `429 Too Many Requests` with `Retry-After` header when throttled
- **Checkpoint:** k6 or a quick curl loop shows requests being throttled correctly and independently per client.

---

## Phase 4 — Circuit Breaker & Fault Tolerance

- Resilience4j `CircuitBreaker` wired into the demo-backend route (already stubbed in `application.yml`)
- Fallback controller (`/fallback/demo`) returning a graceful degraded response
- Simulate backend failure (kill/slow demo-backend) and confirm circuit opens, then half-opens, then closes
- Add `TimeLimiter` for slow-response handling
- **Checkpoint:** Backend outage doesn't cascade — gateway degrades gracefully and self-heals.

---

## Phase 5 — Persistence (PostgreSQL)

- Flyway migrations: `api_keys` / `clients` table (client id, secret/hash, rate-limit tier, enabled flag)
- R2DBC repository layer to look up client rate-limit tier at request time (cached in Redis to avoid DB hit per request)
- Admin endpoint(s) to create/revoke API keys
- **Checkpoint:** Rate limits are dynamic per client, driven by DB config, not just hardcoded defaults.

---

## Phase 6 — Testing

- **Unit tests** (JUnit5 + Mockito): JWT util, rate-limit filter logic, config binding
- **Integration tests** (Testcontainers): spin up real Redis + Postgres containers, verify Lua script behavior under concurrency, verify full filter chain
- **Contract-ish tests** for routing + circuit breaker fallback behavior
- **Checkpoint:** `mvn test` runs a full suite against ephemeral containers, no external dependencies needed locally.

---

## Phase 7 — Dockerization

- `Dockerfile` for gateway and for demo-backend (multi-stage builds)
- `docker-compose.yml`: gateway + demo-backend + Redis + Postgres, wired with env vars, healthchecks, proper startup ordering
- **Checkpoint:** `docker compose up` gives you the entire system running with one command.

---

## Phase 8 — CI/CD (GitHub Actions)

- Workflow: build → unit/integration tests (with Testcontainers, needs Docker-in-Docker or GH's built-in Docker support) → build Docker images → (optionally) push to a registry
- Cache Maven deps between runs
- **Checkpoint:** Every push/PR runs the full test suite automatically; badge in README.

---

## Phase 9 — Load & Performance Testing (k6)

- k6 scripts simulating: normal traffic, burst traffic (to trigger rate limiting), sustained load (to observe circuit breaker under backend stress)
- Capture and document p50/p95/p99 latency, throughput, error rates
- **Checkpoint:** Documented performance numbers demonstrating the rate limiter and circuit breaker behave correctly under load — good portfolio evidence.

---

## Phase 10 — Polish & Documentation

- Architecture diagram + README with setup instructions, design decisions (why token bucket, why Lua, why reactive stack)
- Document trade-offs (e.g., R2DBC vs JPA, sliding window vs token bucket)
- Clean up config, add sensible defaults, tag a `v1.0.0` release
