# Week 7 — Day 7: Spring Cloud Monitoring, Performance & Best Practices

Bổ sung observability cho `order-service` (Spring Boot 3.2, Java 17, PostgreSQL, Kafka + Outbox, Resilience4j) để **phát hiện và khoanh vùng sự cố production trong vài phút** thay vì chờ customer báo rồi đọc log thủ công.

## Cấu trúc

```text
day-7-monitoring-performance/
├── observability-design.md        # Logs / Metrics / Traces, dashboard, alert, performance risks
├── dashboard-design.md            # 4 dashboard + PromQL cho từng panel
├── alert-runbook.md               # 5 alert chính + alert bổ sung, severity, owner, first response
├── incident-analysis.md           # Incident drill: Inventory chậm 3s trong 20% request (7 câu hỏi)
├── custom-metrics.md              # checkout.success/failed, payment.status.unknown, order.stuck.processing
├── application-observability.yml  # Actuator + Prometheus + Tracing + common tags + Resilience4j
└── README.md
```

Source code service: [`../../order-service`](../../order-service). Bài trước: [day-6-production-readiness](../day-6-production-readiness).

## Bài thực hành — đối chiếu yêu cầu

| # | Yêu cầu | Ở đâu |
|---|---|---|
| 1 | Bật Spring Boot Actuator | `spring-boot-starter-actuator` (đã có trong `build.gradle`) |
| 2 | Expose health, metrics, prometheus | [application-observability.yml](application-observability.yml) — `management.endpoints.web.exposure.include` |
| 3 | Custom metric `checkout.success`, `checkout.failed`, `payment.status.unknown`, `order.stuck.processing` | [custom-metrics.md](custom-metrics.md) |
| 4 | Common tags `application`, `environment`, `version` | `management.metrics.tags` |
| 5 | Propagate correlation/trace ID | [observability-design.md § Correlation/trace ID strategy](observability-design.md#correlationtrace-id-strategy) |
| 6 | Thiết kế dashboard | [dashboard-design.md](dashboard-design.md) |
| 7 | Viết 5 alert có action rõ ràng | [alert-runbook.md](alert-runbook.md) |
| 8 | Mô phỏng incident Inventory chậm 3s | [incident-analysis.md](incident-analysis.md) |

## Chạy & kiểm tra

```powershell
# Thêm dependencies tracing (xem header application-observability.yml), copy file vào resources:
Copy-Item week7/day-7-monitoring-performance/application-observability.yml order-service/src/main/resources/

cd order-service
$env:SPRING_PROFILES_ACTIVE="observability"; $env:APP_ENV="local"; $env:APP_VERSION="1.0.0"
$env:TRACING_SAMPLING_PROBABILITY="1.0"
./gradlew bootRun

# Actuator chạy trên management port 8081
curl http://localhost:8081/actuator/health
curl http://localhost:8081/actuator/metrics
curl http://localhost:8081/actuator/metrics/http.server.requests
curl http://localhost:8081/actuator/prometheus
```

Prometheus scrape config:

```yaml
scrape_configs:
  - job_name: order-service
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
    static_configs:
      - targets: ["order-service:8081"]
```

### Mô phỏng incident

Dùng Toxiproxy (hoặc WireMock với `fixedDelayMilliseconds` + `chunkedDribbleDelay` theo tỉ lệ) chèn latency 3s cho 20% request tới Inventory, rồi bắn tải:

```powershell
toxiproxy-cli toxic add -t latency -a latency=3000 --toxicity 0.2 inventory
k6 run --vus 100 --duration 5m checkout-load.js     # POST /orders + GET /orders/{id}
```

Quan sát: p95/p99 POST vs GET, retry amplification, CB state, Tomcat busy threads → xem phân tích trong [incident-analysis.md](incident-analysis.md).

---

## Trả lời nhanh các mục tiêu buổi học

### Logs vs Metrics vs Traces

| | Trả lời | Ví dụ |
|---|---|---|
| **Metrics** | Có bất thường không? xu hướng? → dashboard & alert | p95 checkout tăng, 5xx tăng, Kafka lag tăng |
| **Traces** | Request đi qua đâu, chậm/lỗi ở bước nào? | Gateway → Order → Inventory (3s) → Payment |
| **Logs** | Chính xác lỗi gì, với order nào? | `PAYMENT_STATUS_UNKNOWN orderId=ORD-123 traceId=a8f3…` |

Phát hiện bằng metrics → khoanh vùng bằng traces → chi tiết bằng logs; nối nhau bằng `traceId`/`correlationId`.

### Áp dụng cho câu chuyện flash sale 20:00

Với setup này, vấn đề được phát hiện trong vài phút:

| Phút | Signal | Alert / Dashboard |
|---|---|---|
| +2 | Inventory client p99 200ms → 6s | Dependency dashboard |
| +3 | p99 `POST /orders` > 2s | `CheckoutLatencyCritical` (page) |
| +3 | Retry amplification > 1.2, CB HALF_OPEN/OPEN | `RetryStorm`, `InventoryCircuitOpen` (context) |
| +5 | `outbox_oldest_pending_age_seconds` > 300 | `OutboxBacklog` (page) |
| +5 | `payment_success_order_pending` > 0 | `PaymentOrderMismatch` (page) → reconciliation |
| +10 | Kafka consumer lag tăng liên tục | `KafkaConsumerLagGrowing` |

Thay vì 45 phút đọc log riêng lẻ của từng service.

### Metric quan trọng theo nhóm

| Nhóm | Metric chính |
|---|---|
| HTTP (RED) | rate, error rate (5xx %), p50/p95/p99, in-flight, status distribution |
| JVM | heap after GC, GC pause, allocation rate, thread states, CPU, restart |
| DB pool | Hikari active/pending/acquire time/timeout, slow query, lock wait |
| Circuit breaker | state, failed/slow calls, not-permitted, retry, bulkhead available, fallback |
| Kafka/Outbox/DLQ | consumer lag (trend), throughput, retry, DLQ count, outbox pending, oldest outbox age, duplicate |
| Business | checkout success rate, payment status unknown, order stuck processing, payment-success-order-pending |

### Performance anti-patterns (≥ 3) và cách phát hiện

| Anti-pattern | Phát hiện | Fix |
|---|---|---|
| Retry không giới hạn/không jitter | Traffic tới downstream > inbound; `retry_calls_total` tăng; trace có nhiều span cùng call | Max 1 retry, backoff + jitter, retry budget |
| Timeout dài/không có timeout | p99 bằng đúng giá trị timeout; thread WAITING ở socket read | Timeout < SLO, deadline propagation |
| HTTP call trong `@Transactional` | Hikari active/pending cao nhưng DB CPU thấp | Gọi ngoài transaction, outbox |
| Thiếu bulkhead, read/write chung pool | GET p95 tăng cùng POST dù GET không gọi downstream; Tomcat busy = max | Bulkhead per dependency, tách pool/deployment |
| N+1 query | Số span JDBC/request tỉ lệ với số item | Fetch join, batch |
| `.block()` trong WebFlux | Thread `reactor-http-nio` BLOCKED; BlockHound | Non-blocking end-to-end hoặc dùng MVC |
| Log body mọi request | Log volume tăng theo traffic, lộ PII | Log event + field chọn lọc, masking, sampling |
| High-cardinality tag (`orderId`, `userId`) | `prometheus_tsdb_head_series` tăng vọt | Tag enum; orderId để ở log/trace |

---

## Tiêu chí hoàn thành

- [x] Phân biệt được logs, metrics và traces — [observability-design.md §0](observability-design.md#0-logs-vs-metrics-vs-traces)
- [x] Expose được Actuator metrics/Prometheus endpoint — [application-observability.yml](application-observability.yml)
- [x] Có technical metrics và business metrics — [observability-design.md §2](observability-design.md#2-metrics), [custom-metrics.md](custom-metrics.md)
- [x] Không dùng high-cardinality tag như userId, orderId — [custom-metrics.md §5](custom-metrics.md#5-checklist-cardinality)
- [x] Có trace/correlation ID strategy — [observability-design.md §1, §3](observability-design.md#3-traces)
- [x] Dashboard có API, JVM, DB, event và business signal — [dashboard-design.md](dashboard-design.md)
- [x] Alert có severity, owner và first-response action — [alert-runbook.md](alert-runbook.md)
- [x] Có incident analysis dựa trên evidence, không chỉ đoán — [incident-analysis.md](incident-analysis.md)
- [x] Nêu được ít nhất 3 performance anti-pattern và cách phát hiện — bảng ở trên & [observability-design.md §6](observability-design.md#6-performance-risks)
