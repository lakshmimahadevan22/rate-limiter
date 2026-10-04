# Load & performance testing (k6)

Three scripts exercise the gateway under different traffic shapes. Measured results from a local
run are recorded [below](#recorded-results).

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
so run it with the `demo` client's limit raised first, or the results will just show rate limiting
instead of backend/circuit-breaker behavior. Note that `RATE_LIMIT_CAPACITY` won't help here — it
only sets the *default* tier, and `demo` has its own override:

```sh
DEMO_RATE_LIMIT_CAPACITY=50 DEMO_RATE_LIMIT_REFILL_TOKENS=50 docker compose up --build
k6 run k6/sustained-load.js
```

(Running the jars directly instead of Compose, export the same two variables before starting the
gateway.)

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

## Recorded results

k6 omits p99 by default; pass `--summary-trend-stats="avg,med,p(95),p(99),max"` to include it.

Measured 2026-10-04 on a single local instance of each service (Apple Silicon Mac, gateway,
demo-backend, Redis 8 and Postgres 14 all on localhost). These numbers show the gateway's own
overhead and correctness, not what you'd see across a real network.

| Scenario | p50 | p95 | p99 | Throughput | Unexpected errors | Result |
|---|---|---|---|---|---|---|
| `normal-traffic.js` | 15.5 ms | 19.6 ms | 20.8 ms | 4.0 req/s | 0% (0 / 121) | All thresholds passed |
| `burst-traffic.js` | 8.7 ms | 22.2 ms | 22.7 ms | 60 requests in ~40 ms | 0% | Exactly 5 × `200` (the burst capacity), 55 × `429`, all with `Retry-After` |
| `sustained-load.js` | 12.1 ms (`200`) / 9.0 ms (`503`) | 18.5 / 13.0 ms | 23.3 / 17.9 ms | 8.9 req/s avg, 10 req/s peak | 0% (0 / 2,130) | 1,612 × `200`, 517 × fallback `503` during the induced outage |

"Unexpected errors" means any status other than the ones the scenario is designed to produce:
k6's own `http_req_failed` counts the deliberate `429`s and fallback `503`s as failures.

**Sustained-load timeline** (`demo-backend` killed at t=70s, restarted at t=120s, demo tier raised
to 50 req/s):

| Window | `200` | `503` | What happened |
|---|---|---|---|
| 0–70s | 579 | 0 | Ramp-up to 10 req/s, healthy |
| 70–120s | 0 | 500 | Backend down: every request served by `/fallback/demo` immediately, with no 5xx errors, timeouts or hung connections reaching the client |
| 120–130s | 83 | 17 | Backend booting; breaker half-opens, trial calls succeed, breaker closes |
| 130–240s | 950 | 0 | Fully recovered with no intervention, then the scripted ramp-down |

Fallback responses were *faster* than real ones (p50 9.0 ms vs 12.1 ms), because an open circuit
short-circuits without waiting on the dead backend.

All three scripts log in as the operator account, so every request shares the `demo` client's
single rate-limit bucket. To load-test several independent clients, create them with
`POST /admin/clients` and have each virtual user log in through `POST /auth/client-token` with its
own client ID and API key.
