// Stage 6 — стресс-тест на 3000 заказанных RPS на pool-deepseek.
// Stage 3 показал потолок ~837 RPS из 1000 заказанных на 4g heap + ZGC + 512 scheduler.
// Здесь умножаем rate ×3 и поднимаем VU pools соответственно. Цель — увидеть где сервер
// ломается (dropped iterations / http_req_failed / p99 > 30s threshold).
//
// Распределение rate сохраняем как в stage3 (14:5:1):
//   streaming    = 2100 rps  (700 × 3)
//   nonStreaming =  750 rps  (250 × 3)
//   toolCalls    =  150 rps  ( 50 × 3)
//
// VU pools:
//   На deepseek p99 streaming = 11.5s → 2100 rps × 12s ≈ 25k параллельных VU.
//   Берём maxVUs=40000 с запасом 1.6× против observed peak.
//
// Server-side knobs (запускать через scripts/run.sh с этими env):
//   JAVA_OPTS="-Xms8g -Xmx8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:MaxDirectMemorySize=4g \
//              -Dio.netty.eventLoopThreads=64 -Dkotlinx.coroutines.scheduler.max.pool.size=1024"
//   FAKER_POOL_DIR=pool-deepseek
//
// Thresholds ослаблены под стресс: p99 до 60s, fail-rate до 5% — мы хотим РАЗГЛЯДЕТЬ потолок,
// а не запретить тесту его найти.

import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    streaming: {
      executor: 'constant-arrival-rate',
      rate: 2100,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 15000,
      maxVUs: 40000,
      exec: 'streamingRequest',
    },
    nonStreaming: {
      executor: 'constant-arrival-rate',
      rate: 750,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 2500,
      maxVUs: 8000,
      exec: 'nonStreamingRequest',
    },
    toolCalls: {
      executor: 'constant-arrival-rate',
      rate: 150,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 600,
      maxVUs: 3000,
      exec: 'toolCallRequest',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<30000', 'p(99)<60000'],
    http_req_failed: ['rate<0.05'],
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
    timeout: '90s',
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
    timeout: '90s',
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
    timeout: '90s',
  });
  check(res, { 'tool-call status 200': (r) => r.status === 200 });
}
