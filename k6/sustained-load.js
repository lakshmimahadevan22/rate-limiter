import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const USERNAME = __ENV.AUTH_USERNAME || 'demo';
const PASSWORD = __ENV.AUTH_PASSWORD || 'demo-password';

// Several minutes of sustained traffic, meant to be run with the rate limit raised well above
// this script's rate (see k6/README.md) so it exercises the circuit breaker and backend
// throughput instead of just getting throttled. Pair this with manually stopping/restarting the
// demo-backend container partway through to watch the circuit open, fall back, and recover.
export const options = {
  scenarios: {
    sustained: {
      executor: 'ramping-arrival-rate',
      startRate: 2,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 50,
      stages: [
        { target: 10, duration: '30s' },
        { target: 10, duration: '3m' },
        { target: 0, duration: '30s' },
      ],
    },
  },
};

const successResponses = new Counter('success_responses');
const fallbackResponses = new Counter('circuit_breaker_fallback_responses');
const throttledResponses = new Counter('rate_limited_responses');
const otherErrors = new Counter('other_error_responses');

export function setup() {
  const res = http.post(
    `${BASE_URL}/auth/token`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(res, { 'login succeeded': (r) => r.status === 200 });
  return { token: res.json('accessToken') };
}

export default function (data) {
  const res = http.get(`${BASE_URL}/api/demo/hello`, {
    headers: { Authorization: `Bearer ${data.token}` },
  });

  check(res, {
    'status is 200, 429, or 503': (r) => [200, 429, 503].includes(r.status),
  });

  if (res.status === 200) {
    successResponses.add(1);
  } else if (res.status === 503) {
    fallbackResponses.add(1);
  } else if (res.status === 429) {
    throttledResponses.add(1);
  } else {
    otherErrors.add(1);
  }
}
