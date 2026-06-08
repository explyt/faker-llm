// Stage 4 — ищем потолок сервера, k6 уже не bottleneck.
// Stage 3 показал: сервер легко тянет заказанные 1000 RPS на pool-deepseek без дропов.
// Поднимаем rate агрессивно: streaming 1500, nonStream 500, toolCalls 100 → ~2100 RPS суммарно.
// VU pool увеличен пропорционально: при p99=11.5s × 1500 streaming = ~17000 параллельных VU.

import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    streaming: {
      executor: 'constant-arrival-rate',
      rate: 1500,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 10000,
      maxVUs: 30000,
      exec: 'streamingRequest',
    },
    nonStreaming: {
      executor: 'constant-arrival-rate',
      rate: 500,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 1500,
      maxVUs: 6000,
      exec: 'nonStreamingRequest',
    },
    toolCalls: {
      executor: 'constant-arrival-rate',
      rate: 100,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 400,
      maxVUs: 2000,
      exec: 'toolCallRequest',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<30000', 'p(99)<60000'],
    http_req_failed: ['rate<0.10'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export function streamingRequest() {
  const body = JSON.stringify({
    model: 'faker',
    messages: [{ role: 'user', content: 'Hello, what is the weather?' }],
    stream: true,
  });
  const res = http.post(`${BASE}/v1/chat/completions`, body, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '60s',
  });
  check(res, { 'streaming status 200': (r) => r.status === 200 });
}

export function nonStreamingRequest() {
  const body = JSON.stringify({
    model: 'faker',
    messages: [{ role: 'user', content: 'Hi' }],
  });
  const res = http.post(`${BASE}/v1/chat/completions`, body, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '60s',
  });
  check(res, { 'non-stream status 200': (r) => r.status === 200 });
}

export function toolCallRequest() {
  const body = JSON.stringify({
    model: 'faker',
    stream: true,
    messages: [{ role: 'user', content: '[[faker:force_tag:tool_call]] weather?' }],
    tools: [{
      type: 'function',
      function: { name: 'get_weather', parameters: { type: 'object', properties: { city: { type: 'string' } } } },
    }],
  });
  const res = http.post(`${BASE}/v1/chat/completions`, body, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '60s',
  });
  check(res, { 'tool-call status 200': (r) => r.status === 200 });
}
