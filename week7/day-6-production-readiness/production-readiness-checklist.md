# Production Readiness Checklist – `order-service`

> Danh mục kiểm duyệt bắt buộc (Go-Live Quality Gate) trước khi đưa `order-service` vào môi trường vận hành Production và đón tải Flash Sale.

---

## 1. Đối Chiếu Tiêu Chí Hoàn Thành (Definition of Done)

| Trạng thái | Tiêu chí bắt buộc | Tài liệu / File hiện thực | Bằng chứng kiểm chứng |
| :---: | :--- | :--- | :--- |
| **[x]** | **Service chạy được bằng Docker** | - [Dockerfile](Dockerfile)<br/>- [docker-compose.yml](docker-compose.yml)<br/>- [.dockerignore](../../order-service/.dockerignore) | Multi-stage build (`gradle:8.7-jdk17-alpine` $\rightarrow$ `eclipse-temurin:17-jre-alpine`), Layered JAR, Non-root `10001:10001`, Container-aware JVM (`MaxRAMPercentage=75.0`). |
| **[x]** | **Không để secret trong image/source code** | - [application.yml](../../order-service/src/main/resources/application.yml)<br/>- [docker-compose.yml](docker-compose.yml)<br/>- [.dockerignore](../../order-service/.dockerignore) | `password: ${DB_PASSWORD:}` (không hard-code password); Docker Compose bắt buộc truyền `DB_PASSWORD` từ môi trường ngoài; `.dockerignore` loại bỏ `.env*`, `*.pem`, `*.key`. |
| **[x]** | **Phân biệt Liveness và Readiness** | - [actuator-health-design.md](actuator-health-design.md)<br/>- [application.yml](../../order-service/src/main/resources/application.yml)<br/>- [OutboxBacklogHealthIndicator.java](../../order-service/src/main/java/com/example/orderservice/adapter/out/persistence/OutboxBacklogHealthIndicator.java) | `liveness` chỉ gồm `livenessState,ping` (không phụ thuộc DB/Downstream để chống Restart Storm); `readiness` gồm `readinessState,db,outboxBacklog`. |
| **[x]** | **Có Technical + Business Metrics** | - [observability-dashboard.md](observability-dashboard.md)<br/>- [build.gradle](../../order-service/build.gradle) | Đầy đủ 10 Technical Metrics (RPS, 4xx/5xx, p50/p95/p99, Heap/GC, Tomcat threads, HikariCP, CB state, Kafka lag, DLQ, Outbox) + 5 Business Metrics (Checkout success rate, Payment mismatch, Inventory failure, Dedupe count, Stuck orders). |
| **[x]** | **Có Correlation/Trace ID Strategy** | - [observability-dashboard.md](observability-dashboard.md)<br/>- [CorrelationIdFilter.java](../../order-service/src/main/java/com/example/orderservice/adapter/in/web/CorrelationIdFilter.java) | Tự động trích xuất/sinh `X-Correlation-Id` và `X-Trace-Id` (`traceparent`), nạp vào `MDC`, in ra Structured Console Log và truyền qua HTTP/Outbox/Kafka headers. |
| **[x]** | **Có Incident Runbook cho kịch bản lỗi** | - [incident-runbook.md](incident-runbook.md) | Phân tích chi tiết sự cố Flash Sale 20:00, quy trình 5 bước Triage, Drill Kịch bản A (Inventory chậm 5s), Kịch bản B (Payment timeout sau khi đã charge) và RCA 5 Whys. |

---

## 2. Checklist Chi Tiết Trước Giờ G (Production Go-Live Gate)

### 2.1. Containerization & OS/JVM Hardening
- [x] **Multi-stage Dockerfile**: Tách biệt môi trường build (`gradle` + `jdk`) và môi trường runtime (`jre-alpine`), không để sót compiler hay build cache trong image cuối.
- [x] **Spring Boot Layered JAR**: Giải nén bằng `-Djarmode=layertools` để tối ưu hóa Docker layer caching.
- [x] **`.dockerignore` đầy đủ**: Chặn `.git`, `.gradle`, `build`, `.env*`, `*.pem`, `*.key`, `heapdump*.hprof`.
- [x] **Non-root User**: Chạy ứng dụng dưới quyền `USER 10001:10001`, `read_only: true` cho rootfs và `no-new-privileges: true`.
- [x] **JVM Memory trong Container**: Cấu hình `-XX:+UseContainerSupport -XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0` để chừa 25% RAM cho Off-Heap (Metaspace, Thread Stacks, Direct Buffers), ngăn ngừa lỗi `OOMKilled (Exit Code 137)`.
- [x] **Auto Heap Dump khi OOM**: Bật `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdumps/heapdump.hprof` gắn vào volume riêng.

### 2.2. Quản Lý Cấu Hình & Bảo Mật Secret (12-Factor Config)
- [x] **Zero Hardcoded Secrets**: Không có bất kỳ password, API key, JWT secret nào trong `application.yml`, `Dockerfile`, hay Git history.
- [x] **Externalized Environment Variables**: Mọi URL kết nối (`DB_URL`, `INVENTORY_SERVICE_URL`, `PAYMENT_SERVICE_URL`), kích thước pool (`DB_POOL_MAX_SIZE`) và timeout đều cấu hình được qua biến môi trường mà không cần build lại image.
- [x] **Bảo vệ Actuator Endpoints**: Đặt `management.endpoint.health.show-details: when-authorized`, không phơi bày `/actuator/env`, `/actuator/heapdump`, hoặc `/actuator/shutdown` ra mạng public.

### 2.3. Health Probes & Graceful Shutdown
- [x] **Liveness Probe độc lập**: `/actuator/health/liveness` chỉ kiểm tra `livenessState` và `ping` — tuyệt đối không gọi DB, Kafka hay Downstream Service.
- [x] **Readiness Probe chính xác**: `/actuator/health/readiness` kiểm tra `readinessState`, `db`, và `outboxBacklog`.
- [x] **PID 1 Signal Propagation**: Dùng `exec java ...` trong `ENTRYPOINT` để tiến trình JVM trực tiếp nhận tín hiệu `SIGTERM`.
- [x] **Graceful Shutdown đồng bộ**: `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s` nhỏ hơn `stop_grace_period: 35s` của Docker/K8s để hoàn tất trọn vẹn các giao dịch đang xử lý dở.

### 2.4. Resilience, Database & Thread Pool Isolation
- [x] **Không giữ DB Transaction khi gọi mạng**: `spring.jpa.open-in-view: false`, không bọc `@Transactional` quanh lời gọi HTTP tới Inventory/Payment.
- [x] **Timeout rõ ràng ở mọi Client**: Có `connect-timeout` và `read-timeout` ngắn hơn ngân sách thời gian của API Gateway.
- [x] **Circuit Breaker bắt cả lỗi chậm (Slow Call)**: Cấu hình cả `failure-rate-threshold` lẫn `slow-call-duration-threshold` + `slow-call-rate-threshold`.
- [x] **Retry an toàn + Idempotency Key**: Chỉ retry lỗi transient với Exponential Backoff + Jitter; đối với Payment, tuyệt đối không retry mù quáng khi gặp Read Timeout mà phải đối soát qua `Idempotency-Key`.

### 2.5. Observability, Alerting & Incident Readiness
- [x] **Structured Logging kèm Trace Context**: Mọi dòng log đều có `service`, `traceId`, `correlationId`, `orderId`.
- [x] **Business + Technical Dashboards**: Có đầy đủ 6 nhóm biểu đồ trên Grafana và cảnh báo tự động dựa trên triệu chứng kinh doanh (`Checkout Success Rate < 95%`, `Payment Mismatch > 0`).
- [x] **Runbook & Quy trình Rollback/Replay**: Có hướng dẫn từng bước để xử lý Inventory chậm, Payment timeout lệch tiền, DLQ replay có kiểm soát và Canary Rollback trong vòng $< 2$ phút.

---

## 3. Hướng Dẫn Khởi Chạy & Kiểm Chứng Thực Tế

### 3.1. Khởi chạy toàn bộ Stack bằng Docker Compose

Tại thư mục `week7/day-5-production-readiness`:

```bash
# 1. Truyền biến môi trường Secret khi chạy (không lưu trong code)
export DB_PASSWORD="StrongProdPassword_2026!"
export GRAFANA_ADMIN_PASSWORD="GrafanaAdminPassword_2026!"

# 2. Build multi-stage image và khởi chạy order-service + postgres + prometheus + grafana
docker compose up -d --build
```

### 3.2. Kiểm tra Liveness, Readiness, Metrics & Correlation ID

```bash
# 1. Kiểm tra Liveness Probe (Chỉ kiểm tra nội tại JVM)
curl -i http://localhost:8080/actuator/health/liveness
# -> HTTP/1.1 200 OK | {"status":"UP"}

# 2. Kiểm tra Readiness Probe (Kiểm tra khả năng nhận traffic + DB + Outbox Backlog)
curl -i http://localhost:8080/actuator/health/readiness
# -> HTTP/1.1 200 OK | {"status":"UP"}

# 3. Kiểm tra truyền vết Correlation ID & Trace ID trên API Tạo Đơn Hàng
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: corr-flashsale-test-001" \
  -d '{
    "orderId": "ORD-2026-001",
    "items": [
      { "productId": "SKU-IPHONE-16", "quantity": 1, "price": 999.0 }
    ]
  }'
# -> Response trả về Header:
#    X-Correlation-Id: corr-flashsale-test-001
#    X-Trace-Id: corrflashsaletest001

# 4. Kiểm tra Prometheus Metrics (HikariCP, JVM, Resilience4j, Outbox Backlog)
curl -s http://localhost:8080/actuator/prometheus | grep -E "outbox_unpublished_count|resilience4j_circuitbreaker_state|hikaricp_connections_active"
```

