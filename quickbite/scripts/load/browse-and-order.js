import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const API = __ENV.API || 'http://localhost:8000';
const KC = 'http://localhost:8180/realms/quickbite/protocol/openid-connect/token';
const USERS = 50;
const orderLatency = new Trend('order_latency', true);

export const options = {
  scenarios: {
    browse: { executor: 'ramping-arrival-rate', startRate: 20, timeUnit: '1s', preAllocatedVUs: 100,
              stages: [ { target: 200, duration: '1m' }, { target: 200, duration: '1m' }, { target: 0, duration: '20s' } ],
              exec: 'browse' },
    order:  { executor: 'constant-arrival-rate', rate: 10, timeUnit: '1s', duration: '2m', preAllocatedVUs: 50, exec: 'order' },
  },
  thresholds: {                       // <- these ARE your NFRs, enforced
    'http_req_failed': ['rate<0.01'],
    'http_req_duration{scenario:browse}': ['p(95)<150', 'p(99)<300'],
    'order_latency': ['p(95)<500', 'p(99)<1000'],
  },
};

export function setup() {
  const tokens = [];
  for (let i = 1; i <= USERS; i++) {
    const r = http.post(KC, { grant_type: 'password', client_id: 'cli-test', username: `load${i}`, password: `load${i}` });
    tokens.push(r.json('access_token'));
  }
  return { tokens };
}

export function browse() {
  const r = http.get(`${API}/api/restaurants`, { tags: { name: 'list' } });
  check(r, { 'list 200': (x) => x.status === 200 });
  const id = `r${1 + Math.floor(Math.random() * 6)}`;
  check(http.get(`${API}/api/restaurants/${id}`, { tags: { name: 'detail' } }), { 'detail 200': (x) => x.status === 200 });
}

export function order(data) {
  const token = data.tokens[__VU % data.tokens.length];
  const body = JSON.stringify({ restaurantId: 'r2', items: [{ menuItemId: 'r2-i1', quantity: 1 }], deliveryLat: 12.93, deliveryLon: 77.62 });
  const r = http.post(`${API}/api/orders`, body, { headers: {
      'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': `${__VU}-${__ITER}-${Date.now()}` },
      tags: { name: 'place_order' } });
  orderLatency.add(r.timings.duration);
  check(r, { 'order 201': (x) => x.status === 201 });
}