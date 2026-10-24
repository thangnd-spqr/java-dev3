# Simulation & Evidence – Inventory chậm / 500 / 400

> Số liệu dưới đây là **kết quả kỳ vọng** suy ra từ cấu hình ở [resilience-implementation.md](resilience-implementation.md) (timeout 1s, 3 attempts, backoff 200ms×2 ±50%, CB window 20 / min 10 / 50%, bulkhead 10). Khi chạy thực tế, thay bằng số đo được từ Prometheus/Grafana và log; con số tuyệt đối sẽ khác tuỳ máy nhưng **hình dạng** (shape) phải giống.

## 1. Kịch bản tải – k6

`load/checkout-vs-getorder.js`

```javascript
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    checkout: {            // tải ghi – đi qua Inventory
      executor: 'constant-vus', vus: 30, duration: '60s', exec: 'checkout',
    },
    get_order: {           // tải đọc – KHÔNG được bị ảnh hưởng
      executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s',
      duration: '60s', preAllocatedVUs: 20, exec: 'getOrder',
    },
  },
  thresholds: {
    'http_req_duration{scenario:get_order}': ['p(99)<200'],   // Get Order phải luôn nhanh
    'http_req_failed{scenario:get_order}':   ['rate<0.001'],
    'http_req_duration{scenario:checkout}':  ['p(99)<4000'],  // < gateway timeout 5s
  },
};

const BASE = __ENV.BASE || 'http://localhost:8081';

export function checkout() {
  const res = http.post(`${BASE}/orders`,
    JSON.stringify({ customerId: 'C1', items: [{ sku: 'SKU-1', quantity: 1 }] }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'POST /orders' } });
  check(res, { 'checkout 2xx/4xx/503 (không 500/504)': r => [201, 400, 409, 503].includes(r.status) });
}

export function getOrder() {
  const res = http.get(`${BASE}/orders/ORD-SEED-1`, { tags: { name: 'GET /orders/{id}' } });
  check(res, { 'get order 200': r => r.status === 200 });
}
```

Quy trình cho mỗi kịch bản:

```bash
curl -X POST "http://localhost:8082/chaos?mode=NORMAL"          # reset
curl -X POST "http://localhost:8082/chaos?mode=SLOW&delayMs=3000" # hoặc ERROR_500 / ERROR_400
k6 run load/checkout-vs-getorder.js
# Quan sát song song:
watch -n1 'curl -s localhost:8081/actuator/circuitbreakers | jq .circuitBreakers.inventory'
curl -s localhost:8081/actuator/circuitbreakerevents/inventory | jq '.circuitBreakerEvents[-5:]'
curl -s localhost:8081/actuator/prometheus | grep -E "resilience4j_(retry_calls|circuitbreaker_state|bulkhead_available)"
```

---

## 2. Baseline – trước khi áp dụng (timeout 30s, retry 3 lần ngay, không CB/bulkhead)

Inventory chậm 5s:

| Chỉ số | Giá trị |
|---|---|
| Checkout p99 | ~20s (4 attempts × 5s) |
| Request tới Inventory / checkout | 4 |
| `tomcat_threads_busy_threads` | 200/200 (bão hoà) |
| **Get Order p99** | **> 5s, nhiều 504 từ gateway** ❌ |
| Circuit breaker | Không có → Inventory bị dội tải liên tục |

---

## 3. Kịch bản A – Inventory chậm 3 giây

Diễn biến theo thời gian:

```text
t=0.0s  CLOSED   10 request đầu lấy được permit bulkhead, 20 request còn lại → BulkheadFullException → 503 ngay (~2ms)
t=1.0s  CLOSED   attempt 1 của 10 request đều read timeout (1s) → RetryableException → ghi nhận failure
t=1.2s           retry 1 (backoff ~200ms ± 50%)
t=1.2s  OPEN     CB đã có ≥10 call, failure rate 100% ≥ 50% → CLOSED → OPEN
t=1.2s+          retry 1 nhận CallNotPermittedException → KHÔNG retry tiếp → 503 Retry-After: 10
t=1.2–11s OPEN   mọi checkout fail fast ~3ms, Inventory không nhận thêm request
t=11.2s HALF_OPEN cho 3 request thử → vẫn timeout → OPEN lại (chu kỳ 10s)
```

| Quan sát | Kết quả kỳ vọng |
|---|---|
| Retry count | Vài chục `failed_with_retry` trong giây đầu; sau đó ~0 vì CB open chặn retry |
| Circuit breaker state | CLOSED → **OPEN** sau ~1.2s; OPEN ↔ HALF_OPEN mỗi 10s |
| Checkout latency | Giây đầu: ~1.2–2.2s; khi OPEN: p50 ~3ms, p99 < 50ms |
| Checkout error rate | ~100% 503 (đúng – Inventory hỏng thì phải báo lỗi nhanh, rõ ràng) |
| Request tới Inventory | ~10–20 trong 10s đầu rồi ~3 mỗi 10s (so với hàng nghìn ở baseline) |
| Bulkhead | `available_concurrent_calls` về 0 trong ~1s đầu, sau đó trở lại 10 |
| **Get Order** | **p99 ~15ms, 0% lỗi** ✅ (threshold k6 pass) |

Log mẫu (order-service):

```text
10:20:01.004  INFO [order-service,7f3a..] InventoryGateway   : Calling inventory reserve orderId=ORD-1021 key=order-ORD-1021-reserve
10:20:02.006  WARN [order-service,7f3a..] ResilienceEventLogging : RETRY name=inventory attempt=1 wait=163ms error=feign.RetryableException: Read timed out executing POST http://localhost:8082/api/inventory/reservations
10:20:02.171  WARN [order-service,7f3a..] ResilienceEventLogging : CIRCUIT_BREAKER name=inventory transition=State transition from CLOSED to OPEN
10:20:02.172  WARN [order-service,7f3a..] CheckoutService    : Inventory circuit OPEN, fail fast orderId=ORD-1021
10:20:02.180  WARN [order-service,a01c..] CheckoutService    : Inventory bulkhead full, reject orderId=ORD-1033
10:20:12.172  WARN [order-service,-]      ResilienceEventLogging : CIRCUIT_BREAKER name=inventory transition=State transition from OPEN to HALF_OPEN
10:20:13.175  WARN [order-service,-]      ResilienceEventLogging : CIRCUIT_BREAKER name=inventory transition=State transition from HALF_OPEN to OPEN
```

`GET /actuator/circuitbreakers` (khi OPEN):

```json
{
  "circuitBreakers": {
    "inventory": {
      "failureRate": "100.0%",
      "slowCallRate": "0.0%",
      "failureRateThreshold": "50.0%",
      "slowCallRateThreshold": "50.0%",
      "bufferedCalls": 10,
      "failedCalls": 10,
      "slowCalls": 0,
      "slowFailedCalls": 0,
      "notPermittedCalls": 1873,
      "state": "OPEN"
    }
  }
}
```

Prometheus:

```text
resilience4j_circuitbreaker_state{name="inventory",state="open"} 1.0
resilience4j_circuitbreaker_state{name="inventory",state="closed"} 0.0
resilience4j_circuitbreaker_not_permitted_calls_total{kind="not_permitted",name="inventory"} 1873.0
resilience4j_retry_calls_total{kind="failed_with_retry",name="inventory"} 10.0
resilience4j_bulkhead_available_concurrent_calls{name="inventory"} 10.0
```

> Vì timeout bị tính là **failure** (RetryableException) nên CB mở theo `failure-rate`. Nếu Inventory chậm 900ms (dưới timeout nhưng > `slow-call-duration-threshold: 800ms`), CB sẽ mở theo **slow-call-rate** – bắt được tình trạng "chậm nhưng chưa lỗi".

---

## 4. Kịch bản B – Inventory trả 500

```text
attempt 1: 500 (~8ms) → InventoryTransientException → wait ~200ms
attempt 2: 500        → wait ~400ms
attempt 3: 500        → hết retry → 503 RETRIES_EXHAUSTED  (tổng ~620ms)
Sau ~10 failed calls (≈4 request × 3 attempts) → OPEN
```

| Quan sát | Kết quả kỳ vọng |
|---|---|
| Retry count | 1 request = 3 request tới Inventory **chỉ trước khi CB mở**; `failed_with_retry` tăng ~3–4 rồi dừng |
| Circuit breaker state | CLOSED → OPEN trong < 1s |
| Checkout latency | ~620ms trước khi OPEN; ~3ms sau khi OPEN |
| Error rate | ~100% 503 (không phải 500 – client biết đây là lỗi tạm thời, có `Retry-After`) |
| Get Order | Không đổi ✅ |

Log Inventory chứng minh retry có backoff + jitter và cùng idempotency key:

```text
10:25:00.010 INFO  ReservationController : reserve received#41 key=order-ORD-2001-reserve mode=ERROR_500
10:25:00.236 INFO  ReservationController : reserve received#42 key=order-ORD-2001-reserve mode=ERROR_500   (+226ms)
10:25:00.581 INFO  ReservationController : reserve received#43 key=order-ORD-2001-reserve mode=ERROR_500   (+345ms)
```

---

## 5. Kịch bản C – Inventory trả 400

```text
attempt 1: 400 INVALID_SKU → InventoryBusinessException → ignore (không retry, không ghi failure)
→ Order chuyển REJECTED, trả 400 cho client (~12ms)
```

| Quan sát | Kết quả kỳ vọng |
|---|---|
| Retry count | **0** – `resilience4j_retry_calls_total{kind="failed_without_retry"}` tăng, `failed_with_retry` không đổi |
| Circuit breaker state | **Vẫn CLOSED** – `resilience4j_circuitbreaker_calls_seconds_count{kind="ignored"}` tăng, `failureRate` = 0% |
| Checkout latency | ~10–20ms |
| Error rate | 5xx = 0%; 4xx = 100% (lỗi của request, không phải lỗi hệ thống → không page on-call) |
| Request tới Inventory / checkout | Đúng 1 |
| Get Order | Không đổi ✅ |

```text
10:30:00.120 INFO [order-service,c9d1..] ResilienceEventLogging : RETRY name=inventory ignored (not retryable) error=InventoryBusinessException
```

> Đây là bằng chứng cho "không retry mọi exception": nếu CB tính cả 400 thì một client gửi sai SKU hàng loạt có thể **mở circuit cho tất cả user khác**.

---

## 6. Recovery – Inventory trở lại bình thường

```bash
curl -X POST "http://localhost:8082/chaos?mode=NORMAL"
```

```text
10:35:10.000 WARN CIRCUIT_BREAKER name=inventory transition=State transition from OPEN to HALF_OPEN
              (3 call thử thành công)
10:35:10.090 WARN CIRCUIT_BREAKER name=inventory transition=State transition from HALF_OPEN to CLOSED
```

Checkout trở lại 201, p99 ~60ms, không cần restart service.

---

## 7. Tổng hợp

| Kịch bản | Retry/checkout | CB state | Checkout p99 | Checkout 5xx | Get Order p99 / lỗi |
|---|---|---|---|---|---|
| Baseline (Inventory chậm 5s) | 3 ngay lập tức | – | ~20s | cao (504) | **>5s / có lỗi** ❌ |
| A. Chậm 3s | 0–2, dừng khi OPEN | OPEN ↔ HALF_OPEN | ~2.2s đầu → <50ms | ~100% 503 fail fast | ~15ms / 0% ✅ |
| B. 500 | 2 (trước khi OPEN) | OPEN | ~620ms → <50ms | ~100% 503 | ~15ms / 0% ✅ |
| C. 400 | 0 | CLOSED | ~20ms | 0% (4xx) | ~15ms / 0% ✅ |
| Recovery | 0 | HALF_OPEN → CLOSED | ~60ms | 0% | ~15ms / 0% ✅ |

### Grafana panels gợi ý

```promql
# 1. Circuit breaker state (stat panel, mapping 1 → OPEN màu đỏ)
max by (state) (resilience4j_circuitbreaker_state{name="inventory"})

# 2. Retry theo kind
sum by (kind) (rate(resilience4j_retry_calls_total{name="inventory"}[1m]))

# 3. Checkout vs Get Order p99
histogram_quantile(0.99, sum by (le, method, uri) (rate(http_server_requests_seconds_bucket{uri=~"/orders|/orders/\\{id\\}"}[1m])))

# 4. Error rate checkout
sum(rate(http_server_requests_seconds_count{uri="/orders",status=~"5.."}[1m]))
  / sum(rate(http_server_requests_seconds_count{uri="/orders"}[1m]))

# 5. Bulkhead
resilience4j_bulkhead_available_concurrent_calls{name="inventory"}
```

