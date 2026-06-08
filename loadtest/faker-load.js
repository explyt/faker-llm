import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    streaming: {
      executor: 'constant-arrival-rate',
      rate: 700,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 200,
      maxVUs: 2000,
      exec: 'streamingRequest',
    },
    nonStreaming: {
      executor: 'constant-arrival-rate',
      rate: 250,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 100,
      maxVUs: 500,
      exec: 'nonStreamingRequest',
    },
    toolCalls: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      exec: 'toolCallRequest',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<3000', 'p(99)<5000'],
    http_req_failed: ['rate<0.02'],
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
    timeout: '30s',
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
    timeout: '15s',
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
    timeout: '30s',
  });
  check(res, { 'tool-call status 200': (r) => r.status === 200 });
}
