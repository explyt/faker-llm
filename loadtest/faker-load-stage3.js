// Stage 3 — снимаем k6 с роли bottleneck'а.
// На pool-deepseek p99 streaming = 11.45s (массивные ризонинги по 10k chars).
// 700 req/s × 11.5s = ~8000 параллельных VU только под streaming.
// Берём с запасом: maxVUs 20000, preAllocated 5000, timeout 60s.
// Rate оставляем как в stage2 — хотим понять, может ли сервер выдать честные 1000 RPS
// БЕЗ дропов со стороны генератора. Если выйдет — следующим заходом будем поднимать rate.
//
// Server-side knobs:
//   -Dkotlinx.coroutines.scheduler.max.pool.size=256
//   -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=100
//   FAKER_POOL_DIR=pool-deepseek

import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    streaming: {
      executor: 'constant-arrival-rate',
      rate: 700,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 5000,
      maxVUs: 20000,
      exec: 'streamingRequest',
    },
    nonStreaming: {
      executor: 'constant-arrival-rate',
      rate: 250,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 800,
      maxVUs: 4000,
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
    http_req_duration: ['p(95)<15000', 'p(99)<30000'],
    http_req_failed: ['rate<0.02'],
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
