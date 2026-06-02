# Task 11: Load Test Validation

**Type:** Verification

## Goal

Подтвердить, что фейкер выдерживает целевую нагрузку 1000 RPS на стандартном железе при типичном миксе запросов (streaming + non-streaming, в пропорциях, близких к реальным). Собрать метрики: latency-percentiles, throughput, TTFT. Зафиксировать узкие места и план разруливания.

## What to Do

### Инструмент

Использовать **k6** (`grafana/k6`):
- Stateless, написан на Go — генератор не упрётся в свой потолок
- Нативная поддержка SSE
- Чёткие percentile-метрики в выводе

Альтернативы: `gatling`, `wrk2`. Locust **не подходит** — Python-runtime упрётся.

### Установка k6

- Не в edit-scope. Реализатор предполагает, что k6 уже стоит. Если нет — `brew install k6` делается вручную пользователем; агент **не должен** инсталлить пакеты

### Сценарий нагрузки

Файл `loadtest/faker-load.js`:

```javascript
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
    model: 'gpt-4o-fake',
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
    model: 'gpt-4o-fake',
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
    timeout: '30s',
  });
  check(res, { 'tool-call status 200': (r) => r.status === 200 });
}
```

### Целевые SLO

- `http_req_failed.rate < 0.02` (учитывая 5-10% инжектированных error entries; если пул чисто success — `< 0.005`)
- `p95 < 3000ms`
- `p99 < 5000ms`
- Throughput 1000 RPS все 60 секунд (k6 покажет dropped iterations)

### Запуск

- Фейкер: `./gradlew run` либо собранный fat jar (`java -jar build/libs/faker-llm-all.jar` — рекомендуется без gradle daemon)
- В отдельном терминале: `k6 run loadtest/faker-load.js`
- Параллельно мониторить JVM: `jcmd <pid> VM.native_memory summary`, `jcmd <pid> Thread.print | wc -l`, `top -pid <pid>`

### JVM-параметры

В README отметить рекомендованный запуск:
```
-Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -Dkotlinx.coroutines.scheduler.max.pool.size=64
```

### Зафиксировать в PLAN.md

После прогона записать в Shared Context:
- Итоговый throughput (`http_reqs / duration`)
- p50 / p95 / p99 latency (streaming и non-streaming отдельно если возможно)
- Failure rate
- Пиковый RSS / число тредов / число корутин
- Узкие места: CPU-bound? GC-pause? thread starvation? socket starvation?

## Files/Areas

- `loadtest/faker-load.js`
- `loadtest/README.md` — инструкция запуска
- `PLAN.md` (через update) — финальные результаты в Shared Context

## Key Points

- 1000 RPS — целевая нагрузка. 900 RPS с dropped iterations = **fail**, разбираемся (maxVUs или server-side)
- Streaming-запросы держат соединение секундами — VU pool должен быть **большим**
- На macOS возможна upирка в `ulimit -n` — повысить (`ulimit -n 65536`) перед запуском
- Mac M-series Netty по умолчанию epoll/kqueue. Если нет — добавить `io.netty:netty-transport-native-kqueue:<version>:osx-aarch_64`
- Gotcha: 5% HTTP-error entries в пуле → failure rate в k6 будет нетривиально выше нуля. Это **не** баг. `check` считает 429/500 как fail. Учесть в анализе или отфильтровать через k6-tags

## Done When

- [ ] `loadtest/faker-load.js` создан и запускается
- [ ] `loadtest/README.md` объясняет запуск фейкера, k6, JVM-параметры
- [ ] Прогон 60-секундного теста завершён
- [ ] Результаты в PLAN.md: throughput, p50/p95/p99, failure rate, наблюдения
- [ ] При невыдержанных SLO — открыта отдельная задача с анализом (async-profiler, JFR, `jstack` snapshot)
