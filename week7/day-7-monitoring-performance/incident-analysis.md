# Incident Analysis — Inventory Service chậm 3 giây

## Scenario

Inventory Service chậm **3 giây trong 20% request**.

| Signal | Baseline | Trong sự cố |
|---|---|---|
| Checkout p95 (`POST /orders`) | 300ms | **1.8s** |
| Checkout p99 | 700ms | **6s** |
| Retry count (inventoryService) | ~0 | Tăng mạnh |
| Inventory circuit breaker | CLOSED | HALF_OPEN ↔ OPEN |
| Yêu cầu | Get Order p95 < 300ms | phải giữ |

## Timeline (giả lập)

| Thời điểm | Sự kiện | Signal |
|---|---|---|
| 20:00 | Flash sale bắt đầu, traffic checkout ×5 | Request rate tăng |
| 20:02 | Inventory DB lock contention trên hot SKU → 20% request 3s | `http_client_requests` p99 inventory 200ms → 3s |
| 20:03 | Order retry các call timeout → traffic tới Inventory ×1.2–1.4 | `resilience4j_retry_calls_total{kind="successful_with_retry"}` tăng |
| 20:04 | **CheckoutLatencyCritical** fire (p99 > 2s 5m) — dự kiến | Alert |
| 20:05 | Tomcat busy threads 180/200, `GET /orders/{id}` p95 lên 900ms | Saturation |
| 20:06 | CB inventoryService OPEN → checkout fail fast `INVENTORY_UNAVAILABLE` | `checkout_failed_total{reason="INVENTORY_UNAVAILABLE"}` |
| 20:07 | **CheckoutReliabilityDegraded** fire (success < 99%) | Alert |
| 20:10 | Mitigation: giảm timeout, tắt retry, rate-limit Gateway | p95 giảm về 600ms |
| 20:25 | Inventory team thêm index / tách hot SKU, CB CLOSED | Phục hồi |

---

## Trả lời câu hỏi

### 1. Alert nào cần fire đầu tiên?

**`CheckoutLatencyHigh` / `CheckoutLatencyCritical`** (p95 `POST /orders` > 800ms hoặc p99 > 2s) — đây là **symptom phía user** và xuất hiện sớm nhất (p95 1.8s, p99 6s vượt SLO ngay khi 20% request chậm).

- Ngay sau đó (hoặc cùng lúc) là cảnh báo **dependency** `InventoryCircuitOpen` (warning) và `RetryStorm` (warning) — chúng giải thích *vì sao*, nhưng không nên page riêng (inhibition).
- Khi CB OPEN làm checkout bị từ chối → **`CheckoutReliabilityDegraded`** (critical) fire, đây là alert phản ánh business impact.
- Không nên để alert đầu tiên là CPU/memory: CPU Order Service lúc này thường **thấp** vì thread chỉ đang chờ I/O.

> Nguyên tắc: page theo **symptom** (latency/error mà user cảm nhận), dùng **cause** alert (CB, retry, pool) làm context.

### 2. Dashboard nào xem trước?

1. **Dashboard 1 — Business Health**: checkout success rate, `checkout_failed_total` theo `reason` → xác nhận phạm vi ảnh hưởng (bao nhiêu % checkout, có `payment_success_order_pending` không).
2. **Dashboard 2 — API Gateway & Order**: p50/p95/p99 POST vs GET, 5xx, CB state, retry amplification, Tomcat busy threads, Hikari pending → biết lỗi nằm ở Order và nó đã lan sang GET chưa.
3. **Dashboard 4 — Dependency Health**: p99 client-side `inventory-service` = 3s trong khi Payment & DB bình thường → **khoanh vùng Inventory**. So sánh với p99 server-side của Inventory: nếu server-side cũng 3s → lỗi nằm trong Inventory (DB/lock); nếu server-side nhanh → network/connection pool phía client.
4. Dashboard 3 — Event Processing: kiểm tra phụ, xác nhận outbox/Kafka không bị ảnh hưởng.

### 3. Trace nào giúp xác minh Inventory là dependency chậm?

Query trong Tempo/Jaeger (hoặc click exemplar trên panel p99):

```text
{ resource.service.name = "order-service" && span.http.route = "/orders" && duration > 1.5s }
```

Một trace điển hình:

```text
Trace a8f37e9c  POST /orders  6.2s  (status 201)
Gateway                                    6.21s
└── order-service POST /orders             6.19s
    ├── inventory.reserve  attempt=1       3.00s  ✗ SocketTimeoutException
    ├── (retry backoff)                    0.20s
    ├── inventory.reserve  attempt=2       2.95s  ✓
    │   └── inventory-service POST /reservations 2.93s
    │       └── SELECT ... FOR UPDATE stock (hot SKU)  2.85s   ← root cause
    ├── payment.charge                     0.18s
    └── INSERT orders + outbox             0.03s
```

Bằng chứng:

- Span `inventory.reserve` chiếm > 90% thời gian trace; Payment và DB bình thường.
- Có **2 span `inventory.reserve`** trong một trace → retry đang cộng dồn latency (giải thích p99 = 6s ≈ 2 × 3s).
- Đi xuống span phía Inventory (trace xuyên service) thấy chậm ở query `SELECT … FOR UPDATE` → nguyên nhân gốc trong Inventory DB (lock trên hot SKU), không phải network.
- So sánh với trace **nhanh** cùng thời điểm (80% còn lại): `inventory.reserve` ~150ms → chứng minh chỉ một phần request bị chậm (khớp “20%”).
- Log đối chiếu: `traceId=a8f37e9c event=INVENTORY_TIMEOUT attempt=1`.

### 4. Retry có đang làm traffic tệ hơn không?

**Có.** Bằng chứng & tính toán:

- Metric retry amplification:
  ```promql
  sum(rate(http_client_requests_seconds_count{client_name="inventory-service"}[1m]))
  /
  sum(rate(http_server_requests_seconds_count{uri="/orders",method="POST"}[1m]))
  ```
  Bình thường ≈ 1.0. Với 20% request timeout và `max-attempts: 3`: mỗi request trung bình gửi `1 + 0.2 + 0.2² ≈ 1.24` call (nếu retry cũng 20% chậm), worst case 1.4 → Inventory vốn đang quá tải nhận **thêm 24–40% traffic** đúng lúc yếu nhất.
- Latency cộng dồn: timeout 3s + backoff 1s + retry 3s → request giữ thread tới ~7s → p99 6s.
- Retry giữ thread Tomcat lâu hơn → saturation lan sang endpoint khác (câu 5).
- Lỗi do **lock contention** (không phải lỗi tạm thời ngẫu nhiên) → retry gần như không có cơ hội thành công hơn, chỉ làm lock queue dài thêm.
- Retry `reserve` nếu không có **idempotency key** còn có nguy cơ reserve trùng stock.

Kết luận: retry đang **khuếch đại** sự cố (retry storm). Cần giới hạn: tối đa 1 retry, exponential backoff + jitter, retry budget (≤ 10% traffic), không retry khi CB HALF_OPEN/OPEN, và tổng thời gian (timeout × attempts) phải < SLO.

### 5. Get Order có bị ảnh hưởng không? Nếu có, bulkhead sai ở đâu?

`GET /orders/{id}` **không gọi Inventory**, nên về lý thuyết không được ảnh hưởng. Nhưng thực tế **có**, nếu thấy p95 GET tăng từ < 300ms lên ~900ms cùng thời điểm checkout chậm. Kiểm tra:

| Evidence | Ý nghĩa |
|---|---|
| `tomcat_threads_busy_threads` ≈ `max_threads` (200) | Thread pool web bị checkout chiếm hết |
| `jvm_threads_states_threads{state="TIMED_WAITING"}` tăng, thread dump: nhiều `http-nio-*` chờ ở `SocketInputStream.read` (Inventory client) | Thread bị giữ bởi call Inventory |
| `hikaricp_connections_pending > 0` | Nếu checkout gọi Inventory **bên trong `@Transactional`** → giữ DB connection 3–6s → GET cũng chờ connection |
| Trace GET: span server dài nhưng span DB ngắn, khoảng trống ở đầu | Request phải xếp hàng chờ thread/connection |

Tính bằng Little's Law (`concurrency = throughput × latency`), giả sử 150 rps checkout:

- Bình thường: 150 × 0.3s ≈ **45** thread bận.
- Sự cố: latency trung bình ≈ 0.8 × 0.3 + 0.2 × 6 ≈ 1.44s → 150 × 1.44 ≈ **216** thread > 200 max → Tomcat hết thread, GET phải xếp hàng.

**Bulkhead sai ở đâu:**

1. **Không có bulkhead cho call Inventory**: mọi request checkout được phép chờ Inventory không giới hạn → một dependency chậm chiếm toàn bộ 200 thread Tomcat dùng chung với read path. Phải có `Bulkhead inventoryService` (semaphore, `max-concurrent-calls` ≈ 40, `max-wait-duration` ≈ 50ms) → vượt giới hạn thì fail fast/fallback thay vì giữ thread.
2. **Read path và write path dùng chung tài nguyên**: chung thread pool Tomcat và chung Hikari pool. Nên tách (pool riêng / read replica cho GET, hoặc tách deployment `order-query` khỏi `order-command` để scale & cô lập).
3. **Gọi HTTP trong transaction**: giữ DB connection trong lúc chờ Inventory → bulkhead DB bị vô hiệu. Phải gọi Inventory/Payment **ngoài** transaction, transaction chỉ bao `INSERT order + outbox`.
4. **Bulkhead/timeout đặt sai thứ tự**: timeout (3s) lớn hơn SLO checkout (p95 800ms) và retry nằm **ngoài** CB → mỗi attempt vẫn bị tính & giữ thread. Thứ tự đúng (Resilience4j): `Retry ( CircuitBreaker ( Bulkhead ( TimeLimiter ( call ))))` với timeout ≤ 1s.

### 6. Immediate mitigation

Theo thứ tự ưu tiên (an toàn, nhanh, đảo ngược được):

1. **Giảm/tắt retry** cho `inventoryService` (config/feature flag): `max-attempts: 1` hoặc 2 có jitter → cắt amplification ngay.
2. **Giảm timeout Inventory** xuống ~800ms–1s (`INVENTORY_TIMEOUT_MS`) → giải phóng thread nhanh, p99 bị chặn trên.
3. **Bật bulkhead** cho Inventory (`max-concurrent-calls: 40`) → bảo vệ Tomcat threads, phục hồi `GET /orders` p95 < 300ms.
4. **Để circuit breaker fail fast** với fallback rõ ràng: trả `503` + thông báo “Hệ thống đang bận, vui lòng thử lại” (kèm `Retry-After`), hoặc chuyển order sang `PENDING_RESERVATION` xử lý async nếu business chấp nhận. Không charge Payment khi chưa reserve được.
5. **Rate limit / load shedding ở Gateway** cho `POST /orders` (theo capacity thật của Inventory) → bảo vệ downstream, ưu tiên GET.
6. **Phía Inventory team**: kiểm tra `pg_locks` / slow query trên hot SKU, kill transaction treo, scale Inventory nếu là CPU saturation (scale không giúp nếu là lock contention).
7. **Kiểm tra side effect**: `payment_success_order_pending`, `order_stuck_processing`, outbox backlog → chạy reconciliation nếu có order đã charge nhưng chưa confirm.
8. Thông báo CS & cập nhật status page.

Đo hiệu quả sau mitigation: retry amplification về ≈ 1.0, Tomcat busy < 70%, GET p95 < 300ms, checkout success rate quay về ≥ 99%.

### 7. Long-term fix

| Hạng mục | Hành động |
|---|---|
| Resilience | Bulkhead riêng cho từng dependency; retry budget + exponential backoff + jitter; timeout theo **deadline budget** (Gateway truyền deadline, mỗi hop trừ dần); không retry khi CB không CLOSED |
| Kiến trúc Inventory | Xử lý hot SKU: atomic decrement trên Redis / pre-allocate stock theo bucket, `UPDATE … SET qty = qty - ? WHERE qty >= ?` thay vì `SELECT FOR UPDATE` dài; index đúng |
| Kiến trúc Order | Tách read/write path (CQRS nhẹ, read replica cho GET); không gọi HTTP trong `@Transactional`; reservation async (Saga) cho flash sale |
| Capacity | Load test kịch bản flash sale (k6/Gatling) với fault injection “Inventory 3s 20%” trước mỗi đợt sale; xác định capacity → cấu hình rate limit Gateway |
| Observability | Alert retry amplification & bulkhead rejection; panel POST vs GET cạnh nhau; exemplar → trace; tail sampling giữ 100% trace chậm/lỗi |
| Chaos | Chạy game day định kỳ với Toxiproxy/Chaos Mesh: latency injection vào Inventory, xác nhận GET p95 vẫn < 300ms |
| Quy trình | Postmortem blameless; SLO + error budget cho checkout; runbook cập nhật theo bài học |

---

## Postmortem summary

- **Impact:** ~20 phút checkout chậm (p99 6s), ~X% checkout bị từ chối khi CB OPEN; `GET /orders` bị ảnh hưởng do thread pool saturation.
- **Root cause:** Lock contention trên hot SKU trong Inventory DB → 20% request 3s.
- **Contributing factors:** retry 3 lần không jitter (amplification), timeout lớn hơn SLO, thiếu bulkhead, read/write dùng chung thread pool.
- **Detection:** alert latency (symptom) thay vì chờ customer báo; khoanh vùng bằng dependency dashboard + trace trong < 5 phút.
- **Action items:** xem mục 7, mỗi item có owner + deadline.

