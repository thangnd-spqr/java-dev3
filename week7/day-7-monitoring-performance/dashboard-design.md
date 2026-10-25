# Dashboard Design — Order Platform

> Công cụ: Prometheus + Grafana (metrics), Tempo/Jaeger (traces), Loki/ELK (logs).
> Biến dashboard chung: `$environment`, `$application`, `$version`, `$instance`.
> Mọi panel latency bật **exemplars** → click vào điểm trên graph để mở trace tương ứng.
> Counter luôn xem theo `rate()`/`increase()`, không xem giá trị tích luỹ.

Thứ tự xem khi có sự cố: **1. Business → 2. API → 4. Dependency → 3. Event processing → Trace → Log**.

---

## Dashboard 1 — Executive / Business Health

Mục tiêu: business có đang bị ảnh hưởng không? (dành cho on-call + product + CS)

| Panel | Loại | PromQL | Ngưỡng màu |
|---|---|---|---|
| Checkout success rate | Stat + time series | `sum(rate(checkout_success_total[5m])) / (sum(rate(checkout_success_total[5m])) + sum(rate(checkout_failed_total[5m])))` | xanh ≥ 99%, vàng 97–99%, đỏ < 97% |
| Checkout failed theo reason | Stacked bar | `sum by (reason) (rate(checkout_failed_total[5m]))` | |
| Payment success rate | Stat | `sum(rate(payment_result_total{result="success"}[5m])) / sum(rate(payment_result_total[5m]))` | |
| Payment success but order pending | Stat (lớn, đỏ nếu > 0) | `max(payment_success_order_pending)` | > 0 = đỏ |
| Payment status unknown | Time series | `sum(increase(payment_status_unknown_total[5m]))` | |
| Orders stuck PAYMENT_PROCESSING | Stat | `max(order_stuck_processing)` | > 0 vàng, > 10 đỏ |
| Inventory reservation failure rate | Time series | `sum(rate(inventory_reservation_failed_total[5m])) / sum(rate(checkout_attempt_total[5m]))` | |
| Duplicate payment prevented | Time series | `sum(increase(payment_duplicate_prevented_total[5m]))` | |
| Order volume / phút | Time series (so với cùng giờ tuần trước) | `sum(increase(checkout_success_total[1m]))` và `... offset 7d` | |
| Revenue / phút | Time series | `sum(increase(checkout_amount_sum[1m]))` (DistributionSummary `checkout.amount`) | |

---

## Dashboard 2 — API Gateway & Order Service

### Row: Traffic & Errors (RED)

| Panel | PromQL |
|---|---|
| Request rate theo route | `sum by (uri, method) (rate(http_server_requests_seconds_count{application="order-service"}[1m]))` |
| Gateway request rate theo route | `sum by (routeId) (rate(spring_cloud_gateway_requests_seconds_count[1m]))` |
| 5xx rate (%) | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))` |
| Status distribution | `sum by (status) (rate(http_server_requests_seconds_count[5m]))` |
| Rate-limit rejected (429) ở Gateway | `sum(rate(spring_cloud_gateway_requests_seconds_count{httpStatusCode="429"}[5m]))` |
| In-flight requests | `sum(http_server_requests_active_seconds_active_count)` |

### Row: Latency

| Panel | PromQL |
|---|---|
| p50/p95/p99 `POST /orders` | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/orders",method="POST"}[5m])))` (lặp với 0.5, 0.99) |
| p95 `GET /orders/{id}` | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/orders/{id}",method="GET"}[5m])))` |
| % request đạt SLO 800ms | `sum(rate(http_server_requests_seconds_bucket{uri="/orders",method="POST",le="1.0"}[5m])) / sum(rate(http_server_requests_seconds_count{uri="/orders",method="POST"}[5m]))` (bucket SLO cấu hình sẵn) |
| Latency heatmap | Heatmap từ `http_server_requests_seconds_bucket` |

> Đặt p95 của POST và GET cạnh nhau: nếu GET (không gọi downstream) cũng tăng → saturation chung (thread pool / DB pool), thiếu bulkhead.

### Row: Resilience

| Panel | PromQL |
|---|---|
| Circuit breaker state | `max by (name, state) (resilience4j_circuitbreaker_state)` (State timeline) |
| CB failure rate / slow call rate | `resilience4j_circuitbreaker_failure_rate`, `resilience4j_circuitbreaker_slow_call_rate` |
| Not permitted calls | `sum by (name) (rate(resilience4j_circuitbreaker_not_permitted_calls_total[1m]))` |
| Retry calls theo kind | `sum by (name, kind) (rate(resilience4j_retry_calls_total[1m]))` |
| Retry amplification | `sum(rate(http_client_requests_seconds_count{client_name="inventory-service"}[1m])) / sum(rate(http_server_requests_seconds_count{uri="/orders",method="POST"}[1m]))` (bình thường ≈ 1.0) |
| Bulkhead available | `resilience4j_bulkhead_available_concurrent_calls` |
| Fallback count | `sum by (dependency) (rate(checkout_fallback_total[1m]))` |

### Row: JVM & Saturation

| Panel | PromQL |
|---|---|
| Heap used / max | `sum(jvm_memory_used_bytes{area="heap"}) by (instance) / sum(jvm_memory_max_bytes{area="heap"}) by (instance)` |
| Live data after GC | `jvm_gc_live_data_size_bytes` |
| GC pause p99 | `histogram_quantile(0.99, sum by (le) (rate(jvm_gc_pause_seconds_bucket[5m])))` hoặc `rate(jvm_gc_pause_seconds_sum[1m])` |
| Allocation rate | `rate(jvm_gc_memory_allocated_bytes_total[1m])` |
| Thread states | `sum by (state) (jvm_threads_states_threads)` |
| Tomcat busy / max | `tomcat_threads_busy_threads / tomcat_threads_config_max_threads` |
| CPU | `process_cpu_usage` |
| Pod restarts | `increase(kube_pod_container_status_restarts_total{container="order-service"}[15m])` |

### Row: DB Pool

| Panel | PromQL |
|---|---|
| Active / idle / max | `hikaricp_connections_active`, `hikaricp_connections_idle`, `hikaricp_connections_max` |
| Pending | `hikaricp_connections_pending` |
| Acquire time p95 | `histogram_quantile(0.95, sum by (le) (rate(hikaricp_connections_acquire_seconds_bucket[5m])))` |
| Connection timeouts | `increase(hikaricp_connections_timeout_total[5m])` |

---

## Dashboard 3 — Event Processing

| Panel | PromQL |
|---|---|
| Consumer lag theo group | `sum by (consumergroup, topic) (kafka_consumergroup_lag)` |
| Lag trend (deriv) | `deriv(sum by (consumergroup) (kafka_consumergroup_lag)[10m:1m])` (> 0 kéo dài = không theo kịp) |
| Consumer throughput | `sum by (spring_kafka_listener_id) (rate(spring_kafka_listener_seconds_count[1m]))` |
| Consumer error rate | `sum(rate(spring_kafka_listener_seconds_count{result="failure"}[5m])) / sum(rate(spring_kafka_listener_seconds_count[5m]))` |
| Processing latency p95 | `histogram_quantile(0.95, sum by (le, spring_kafka_listener_id) (rate(spring_kafka_listener_seconds_bucket[5m])))` |
| Retry count | `sum by (topic) (rate(event_consumer_retry_total[5m]))` |
| DLQ messages | `sum by (topic, reason) (increase(event_dlq_total[15m]))` |
| Outbox pending | `max(outbox_pending_events)` |
| Oldest unpublished outbox age | `max(outbox_oldest_pending_age_seconds)` (đỏ > 300s) |
| Outbox publish rate / fail | `sum by (result) (rate(outbox_publish_total[1m]))` |
| Duplicate event detected | `sum by (topic) (increase(event_duplicate_detected_total[5m]))` |

---

## Dashboard 4 — Dependency Health

| Panel | PromQL |
|---|---|
| Inventory p50/p95/p99 (client-side) | `histogram_quantile(0.99, sum by (le) (rate(http_client_requests_seconds_bucket{client_name="inventory-service"}[5m])))` |
| Inventory error/timeout rate | `sum(rate(http_client_requests_seconds_count{client_name="inventory-service",outcome!="SUCCESS"}[5m])) / sum(rate(http_client_requests_seconds_count{client_name="inventory-service"}[5m]))` |
| Payment p95/p99 + error | tương tự với `client_name="payment-service"` |
| Inventory server-side p99 (so sánh với client-side) | `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{application="inventory-service"}[5m])))` — chênh lệch lớn = network/queue/pool phía client |
| PostgreSQL active connections / max | `sum(pg_stat_activity_count) / pg_settings_max_connections` |
| Lock wait | `sum by (mode) (pg_locks_count)` |
| Slow queries | `pg_stat_statements` top theo `mean_exec_time` (panel table) |
| Kafka broker up / under-replicated partitions | `kafka_brokers`, `kafka_topic_partition_under_replicated_partition` |
| Cache hit rate | `sum(rate(cache_gets_total{result="hit"}[5m])) / sum(rate(cache_gets_total[5m]))` |
| Cache evictions | `rate(cache_evictions_total[5m])` |

---

## Liên kết giữa các dashboard

- Panel nào cũng có link “View traces” sang Tempo với filter `service.name` + `http.route` + khoảng thời gian.
- Log panel (Loki) lọc theo `traceId` lấy từ exemplar.
- Annotation deploy (`version` thay đổi) trên mọi dashboard để so sánh trước/sau deploy.

