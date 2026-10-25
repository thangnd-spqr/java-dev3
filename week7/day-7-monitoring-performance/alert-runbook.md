# Alert Runbook — Order Service

## Nguyên tắc

1. Alert phải gắn với **ảnh hưởng user/business** hoặc là **dấu hiệu sớm** chắc chắn dẫn tới ảnh hưởng.
2. Mỗi alert có: **điều kiện (rate + duration)**, **severity**, **owner**, **first-response action**, **link dashboard/runbook**.
3. Severity:
   - **Critical** → page on-call ngay (24/7).
   - **Warning** → Slack channel `#order-alerts`, xử lý trong giờ làm việc hoặc khi đi kèm critical.
4. Có `for:` để tránh flapping; có điều kiện volume tối thiểu để tránh false positive khi traffic thấp (2 request lỗi / 3 request = 66%).
5. Dùng **inhibition** trong Alertmanager: khi `CheckoutReliabilityDegraded` đang fire thì không page thêm `InventoryCircuitOpen` / `CheckoutLatencyHigh` cùng service (vẫn hiện trong notification group).

### Anti-pattern alert (không làm)

| Alert noise | Vì sao | Thay bằng |
|---|---|---|
| CPU > 80% một lần → page | CPU spike ngắn là bình thường, không chắc ảnh hưởng user | Latency SLO + CPU saturation kéo dài 15 phút (warning) |
| Heap > 70% → page | JVM dùng heap đến khi GC là hành vi bình thường | `jvm_gc_live_data_size_bytes` tăng liên tục / OOMKill |
| Một 404 → page | 404 là lỗi client | 5xx rate theo % + volume |
| Log có chữ `ERROR` → page | Không có rate, không có context | Counter metric cho event cụ thể + ngưỡng |

---

## 5 alert chính (bắt buộc)

### A1. CheckoutReliabilityDegraded — Critical

```yaml
- alert: CheckoutReliabilityDegraded
  expr: |
    (
      sum(rate(checkout_success_total{environment="prod"}[5m]))
      /
      (sum(rate(checkout_success_total{environment="prod"}[5m])) + sum(rate(checkout_failed_total{environment="prod",reason!="VALIDATION"}[5m])))
    ) < 0.99
    and
    (sum(rate(checkout_success_total{environment="prod"}[5m])) + sum(rate(checkout_failed_total{environment="prod"}[5m]))) > 1
  for: 5m
  labels: { severity: critical, team: order }
  annotations:
    summary: "Checkout success rate < 99% trong 5 phút"
    dashboard: "grafana/d/business-health"
    runbook: "week7/day-7-monitoring-performance/alert-runbook.md#a1"
```

- **Owner:** Order team on-call.
- **First response:**
  1. Business dashboard → panel *Checkout failed theo reason*: lỗi dồn vào reason nào (`INVENTORY_UNAVAILABLE`, `PAYMENT_TIMEOUT`, `INTERNAL`)?
  2. API dashboard → 5xx rate Gateway vs Order: lỗi ở Gateway (route/rate-limit) hay Order?
  3. Dependency dashboard → Inventory/Payment error & p99.
  4. Tempo: query `service.name=order-service status=error http.route=/orders` → mở 3–5 trace, xem span lỗi.
  5. Có deploy gần đây (annotation `version`)? → cân nhắc rollback.
  6. Báo CS trên `#incident` kèm phạm vi ảnh hưởng (% checkout lỗi).

### A2. CheckoutLatencyHigh — Warning / Critical

```yaml
- alert: CheckoutLatencyHigh
  expr: |
    histogram_quantile(0.95,
      sum by (le) (rate(http_server_requests_seconds_bucket{application="order-service",uri="/orders",method="POST"}[5m]))
    ) > 0.8
  for: 10m
  labels: { severity: warning, team: order }
- alert: CheckoutLatencyCritical
  expr: |
    histogram_quantile(0.99,
      sum by (le) (rate(http_server_requests_seconds_bucket{application="order-service",uri="/orders",method="POST"}[5m]))
    ) > 2
  for: 5m
  labels: { severity: critical, team: order }
```

- **Owner:** Order team.
- **First response:**
  1. Dependency dashboard: p99 client-side Inventory/Payment có tăng cùng lúc không?
  2. Resilience row: retry rate, CB state, retry amplification > 1.2?
  3. DB pool: `hikaricp_connections_pending > 0`?
  4. JVM: GC pause trùng thời điểm? Tomcat busy threads gần max?
  5. Mở trace chậm nhất qua exemplar → xác định span chiếm thời gian.
  6. Nếu do dependency: áp dụng mitigation ở [incident-analysis.md](incident-analysis.md#6-immediate-mitigation).

### A3. PaymentOrderMismatch — Critical

```yaml
- alert: PaymentOrderMismatch
  expr: max(payment_success_order_pending{environment="prod"}) > 0
  for: 5m
  labels: { severity: critical, team: order, notify: payment }
  annotations:
    summary: "Có {{ $value }} order đã charge thành công nhưng chưa CONFIRMED > 5 phút"
```

- **Owner:** Order team (primary), Payment team (secondary).
- **First response:**
  1. **Tạm dừng retry không an toàn** tới Payment (retry không có idempotency key) — bật feature flag `payment.retry.enabled=false` nếu cần.
  2. Kiểm tra `outbox_oldest_pending_age_seconds` và `payment_status_unknown_total`: nguyên nhân là outbox kẹt hay Payment timeout?
  3. Lấy danh sách order (SQL query `status='PAYMENT_PROCESSING' AND payment_status='CAPTURED'`), tra log bằng `orderId`/`correlationId`.
  4. Chạy **reconciliation job** (đối chiếu Payment provider ↔ Order DB) để chuyển order sang `CONFIRMED` hoặc tạo refund.
  5. Thông báo CS danh sách order bị ảnh hưởng, không yêu cầu khách thanh toán lại.

### A4. OutboxBacklog — Critical

```yaml
- alert: OutboxBacklog
  expr: max(outbox_oldest_pending_age_seconds{environment="prod"}) > 300
  for: 2m
  labels: { severity: critical, team: order }
```

- **Owner:** Order team.
- **First response:**
  1. `outbox_publish_total{result="failed"}` có tăng? → xem log `OUTBOX_PUBLISH_FAILED` (Kafka auth, topic không tồn tại, message quá lớn).
  2. Publisher còn chạy không (leader lock / scheduler bị chết)? Pod có restart không?
  3. Kafka broker health, `spring_kafka_template_seconds` p99.
  4. DB: query poll outbox có chậm (thiếu index `status, created_at`) hay bị lock?
  5. Fix rồi theo dõi `outbox_pending_events` giảm về baseline. Không xoá record outbox thủ công.

### A5. CriticalEventInDLQ — Critical

```yaml
- alert: CriticalEventInDLQ
  expr: sum by (topic, reason) (increase(event_dlq_total{topic=~"order-events|payment-events"}[5m])) > 0
  for: 0m
  labels: { severity: critical }
```

- **Owner:** Team sở hữu consumer (Notification/Audit/Order).
- **First response:**
  1. Xem `reason` (`DESERIALIZATION`, `VALIDATION`, `DEPENDENCY_FAILURE`, `UNKNOWN`) và số lượng.
  2. Lấy 1 message mẫu (header `eventId`, `correlationId`) → tra log & trace.
  3. Phân loại: lỗi dữ liệu (poison message) vs lỗi tạm thời (dependency down).
  4. **Không replay mù quáng**: chỉ replay sau khi fix nguyên nhân và xác nhận consumer idempotent (xem [dlq-runbook.md](../day-3-event-driven/dlq-runbook.md)).

---

## Alert bổ sung

| Alert | Điều kiện (PromQL) | for | Severity | Owner | First response |
|---|---|---|---|---|---|
| KafkaConsumerLagGrowing | `deriv(sum by (consumergroup) (kafka_consumergroup_lag)[10m:1m]) > 0 and sum by (consumergroup) (kafka_consumergroup_lag) > 1000` | 10m | Warning | Team consumer | So sánh throughput vs produce rate; consumer error/retry; số partition vs số instance; scale consumer |
| DbPoolExhaustion | `max(hikaricp_connections_pending{application="order-service"}) > 0` | 5m | Warning | Order + DBA | `pg_stat_activity` tìm query dài / idle in transaction; lock; kiểm tra HTTP call trong transaction |
| InventoryCircuitOpen | `max(resilience4j_circuitbreaker_state{name="inventoryService",state="open"}) == 1` | 2m | Warning (Critical nếu A1 cũng fire) | Order team, notify Inventory | Kiểm tra Inventory dashboard; đánh giá số checkout bị từ chối/pending; xác nhận fallback hoạt động |
| RetryStorm | `sum(rate(resilience4j_retry_calls_total{kind=~".*_with_retry"}[5m])) / sum(rate(resilience4j_retry_calls_total[5m])) > 0.2` | 5m | Warning | Order team | Giảm max-attempts / tắt retry tạm thời; kiểm tra downstream |
| OrdersStuckProcessing | `max(order_stuck_processing) > 10` | 10m | Warning | Order team | Kiểm tra Payment callback, outbox, reconciliation job |
| JvmMemoryLeakSuspect | `deriv(jvm_gc_live_data_size_bytes[1h]) > 0 and jvm_gc_live_data_size_bytes / jvm_gc_max_data_size_bytes > 0.8` | 30m | Warning | Order team | Heap dump trên 1 pod (rút khỏi traffic trước), phân tích MAT |

## Routing (Alertmanager)

```yaml
route:
  group_by: [alertname, application, environment]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 2h
  routes:
    - matchers: [severity="critical"]
      receiver: pagerduty-order-oncall
    - matchers: [severity="warning"]
      receiver: slack-order-alerts
inhibit_rules:
  - source_matchers: [alertname="CheckoutReliabilityDegraded"]
    target_matchers: [alertname=~"CheckoutLatencyHigh|InventoryCircuitOpen|RetryStorm"]
    equal: [environment]
```
