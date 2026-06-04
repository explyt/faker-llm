// Stage 7 — нагрузочный тест 1500 заказанных RPS на pool-deepseek
// (после миграции на faker-contract v2).
//
// Контекст: stage3 показал ~837 RPS, stage6 (3000 заказанных) уперся в macOS FD wall
// при ~1190 RPS. 1500 чуть выше observed ceiling — посмотрим как держится.
//
// Распределение rate сохраняем как в stage3 / stage6 (14:5:1):
//   streaming    = 1050 rps  (700 × 1.5)
//   nonStreaming =  375 rps  (250 × 1.5)
//   toolCalls    =   75 rps  ( 50 × 1.5)
//
// VU pools масштабированы пропорционально stage6 (×0.5).
//
// Server-side knobs (запускать через scripts/run.sh с этими env):
//   JAVA_OPTS="-Xms8g -Xmx8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:MaxDirectMemorySize=4g \
//              -Dio.netty.eventLoopThreads=64 -Dkotlinx.coroutines.scheduler.max.pool.size=1024"
//   FAKER_POOL_DIR=pool-deepseek

import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    streaming: {
      executor: 'constant-arrival-rate',
      rate: 1050,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 8000,
      maxVUs: 20000,
      exec: 'streamingRequest',
    },
    nonStreaming: {
      executor: 'constant-arrival-rate',
      rate: 375,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 1500,
      maxVUs: 4000,
      exec: 'nonStreamingRequest',
    },
    toolCalls: {
      executor: 'constant-arrival-rate',
      rate: 75,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 400,
      maxVUs: 1500,
      exec: 'toolCallRequest',
    },
  },
  thresholds: {
    // Ослаблены под границы пропускной способности (от stage6 знаем что 1190 RPS — потолок).
    http_req_duration: ['p(95)<30000', 'p(99)<60000'],
    http_req_failed: ['rate<0.10'],
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
    timeout: '90s',
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
    timeout: '90s',
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
    timeout: '90s',
  });
  check(res, { 'tool-call status 200': (r) => r.status === 200 });
}
