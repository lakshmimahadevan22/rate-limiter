import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const USERNAME = __ENV.AUTH_USERNAME || 'demo';
const PASSWORD = __ENV.AUTH_PASSWORD || 'demo-password';

// Fires far more requests than the "demo" client's tier allows, all at once, to prove the
// Redis token bucket throttles correctly: a handful of 200s (the burst capacity) followed by
// 429s carrying a Retry-After header for everything beyond it.
export const options = {
  scenarios: {
    burst: {
      executor: 'per-vu-iterations',
      vus: 20,
      iterations: 3,
      maxDuration: '15s',
    },
  },
};

const okResponses = new Counter('burst_ok_responses');
const throttledResponses = new Counter('burst_throttled_responses');

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

  const isThrottled = res.status === 429;
  check(res, {
    'status is 200 or 429': (r) => r.status === 200 || r.status === 429,
    'throttled response has Retry-After header': (r) =>
      !isThrottled || r.headers['Retry-After'] !== undefined,
  });

  if (isThrottled) {
    throttledResponses.add(1);
  } else if (res.status === 200) {
    okResponses.add(1);
  }
}
