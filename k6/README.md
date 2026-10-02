# Load & performance testing (k6)

Three scripts exercise the gateway under different traffic shapes. **None of these have been run
against a live stack in this repository** — install k6 and run them yourself against a running
`docker compose up` stack (or `mvn spring-boot:run` for both modules) to get real numbers; don't
treat any numbers here as measured results until you've captured them.

## Prerequisites

- [k6](https://k6.io/docs/get-started/installation/) installed locally.
- The gateway and demo-backend running and reachable, e.g. via `docker compose up --build` (see
  the root README). Default `BASE_URL` is `http://localhost:8080`.

## Scripts

### `normal-traffic.js`

Steady traffic at 4 req/s — comfortably under the `demo` client's default tier (5 req/s burst and
refill) — for 30 seconds. Demonstrates baseline latency and throughput with no throttling.
Thresholds fail the run if more than 1% of requests error, p95 latency exceeds 300ms, or more than
1% of checks fail.

```sh
k6 run k6/normal-traffic.js
```

### `burst-traffic.js`

20 virtual users each fire 3 requests back-to-back with no pacing — 60 requests landing in a tight
window against a client whose burst capacity is 5. Expect a handful of `200`s followed by `429`s
carrying a `Retry-After` header, proving the Redis token bucket throttles correctly under
concurrent access.

```sh
k6 run k6/burst-traffic.js
```

### `sustained-load.js`

Ramps to 10 req/s and holds it for ~3 minutes. **This exceeds the default `demo` tier (5 req/s)**,
so run it with the rate limit raised first, or the results will just show rate limiting instead of
backend/circuit-breaker behavior:

```sh
RATE_LIMIT_CAPACITY=50 RATE_LIMIT_REFILL_TOKENS=50 docker compose up --build
k6 run k6/sustained-load.js
```

While it's running, stop and restart `demo-backend` partway through to watch the circuit breaker
open (responses switch to `503` from the `/fallback/demo` endpoint), then close again once the
backend is healthy and the half-open trial calls succeed:

```sh
docker compose stop demo-backend
# wait ~10-20s, watch fallback responses in the k6 output
docker compose start demo-backend
```

## Environment variables

All three scripts accept:

| Variable | Default | Purpose |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | Gateway base URL |
| `AUTH_USERNAME` | `demo` | Login username for `/auth/token` |
| `AUTH_PASSWORD` | `demo-password` | Login password for `/auth/token` |

## Recording results

After running each script, k6 prints a summary including `http_req_duration` (avg/min/med/max and
p90/p95) and `http_req_failed`. Fill these in from your own runs — this table is a template, not
a report:

| Scenario | p50 | p95 | p99 | Throughput | Error rate | Notes |
|---|---|---|---|---|---|---|
| `normal-traffic.js` | | | | | | |
| `burst-traffic.js` | | | | | | |
| `sustained-load.js` | | | | | | |

A known limitation worth noting when interpreting results: `/auth/token` only ever authenticates
the single hardcoded `demo`/`demo-password` user, so every script run here acts as one client
(JWT subject `demo`) sharing one rate-limit bucket. The admin API (`/admin/clients`) can configure
distinct tiers for other client IDs, but there's currently no way to actually log in as one of
those clients over HTTP — only internal code holding a `JwtUtil` reference can mint a token for an
arbitrary subject. Multi-tenant load testing would need `/auth/token` (or a new endpoint) extended
to authenticate against the `clients` table, e.g. by API key.
