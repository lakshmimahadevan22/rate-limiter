import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const USERNAME = __ENV.AUTH_USERNAME || 'demo';
const PASSWORD = __ENV.AUTH_PASSWORD || 'demo-password';

// Steady traffic comfortably under the "demo" client's default tier (5 req/s burst and
// refill). Demonstrates baseline gateway latency and throughput with no throttling.
export const options = {
  scenarios: {
    normal_traffic: {
      executor: 'constant-arrival-rate',
      rate: 4,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 10,
      maxVUs: 20,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<300'],
    checks: ['rate>0.99'],
  },
};

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

  check(res, { 'status is 200': (r) => r.status === 200 });
}
