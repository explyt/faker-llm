// Stage 4b — targeted 1500 RPS суммарно для прогона ЧЕРЕЗ docker (bridge networking).
// На маке docker desktop добавляет 2-3x latency overhead vs нативный jar,
// поэтому stage4 (2100 RPS) грегнулся, а stage3 (1000 RPS) прошёл.
// Промежуточная точка: streaming 1100, nonStreaming 350, toolCalls 50 → 1500 RPS заказанных.

import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    streaming: {
      executor: 'constant-arrival-rate',
      rate: 1100,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 8000,
      maxVUs: 25000,
      exec: 'streamingRequest',
    },
    nonStreaming: {
      executor: 'constant-arrival-rate',
      rate: 350,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 1200,
      maxVUs: 5000,
      exec: 'nonStreamingRequest',
    },
    toolCalls: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 200,
      maxVUs: 1000,
      exec: 'toolCallRequest',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export function streamingRequest() {
  const body = JSON.stringify({
    model: 'gpt-4o-fake',
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
    model: 'gpt-4o-fake',
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
    model: 'gpt-4o-fake',
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
