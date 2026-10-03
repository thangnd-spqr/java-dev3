# 6. Chaos Engineering Basics

## Chaos Engineering là gì?

Chaos Engineering là phương pháp **chủ động inject lỗi vào hệ thống** (trong môi trường có kiểm soát) để phát hiện điểm yếu trước khi chúng gây incident thật. Đây không phải random testing — mỗi experiment đều có hypothesis rõ ràng, phạm vi giới hạn, và abort condition.

## Các khái niệm cốt lõi

| Khái niệm | Giải thích |
|---|---|
| **Steady State** | Trạng thái "bình thường" của hệ thống, đo bằng các metric cụ thể (success rate, latency…) |
| **Hypothesis** | Giả thuyết: hệ thống sẽ vẫn hoạt động đúng khi gặp lỗi X |
| **Blast Radius** | Phạm vi ảnh hưởng của experiment — cần giữ nhỏ nhất có thể |
| **Guardrail** | Các rào chắn bảo vệ, đảm bảo experiment không gây hại ngoài dự kiến |
| **Abort Condition** | Điều kiện dừng experiment ngay lập tức khi vượt ngưỡng cho phép |
| **Observability** | Khả năng quan sát hệ thống (metrics, logs, traces) — **bắt buộc phải có trước khi chạy experiment** |
| **Game Day** | Buổi diễn tập chaos có kế hoạch, thường chạy ở staging trước |

## Resilience vs Reliability

- **Reliability**: hệ thống hoạt động đúng trong điều kiện bình thường (uptime cao).
- **Resilience**: hệ thống **phục hồi nhanh** khi gặp lỗi — chaos engineering kiểm chứng resilience.

---

## Experiment Design

### Scenario

> **Order Service gọi Inventory Service để reserve stock.**

### 1. Steady State

- Reserve stock success rate ≥ 99.5%
- API response time p95 < 300ms
- Order creation error rate < 0.5%

### 2. Hypothesis

> Nếu một Inventory Service instance bị down, Order Service vẫn reserve stock thành công nhờ retry + fallback (trả về "pending reservation"), và error rate không vượt quá 1%.

### 3. Fault Injection

- **Phương pháp**: Kill 1 trong 3 Inventory Service instance (pod).
- **Môi trường**: Staging environment.
- **Thời gian**: 5 phút.
- **Công cụ**: Kubernetes pod delete / Chaos Mesh / Litmus.

### 4. Blast Radius

- Chỉ ảnh hưởng **1 instance** Inventory Service (còn 2 instance hoạt động).
- Chỉ chạy trên **staging**, không ảnh hưởng production.
- Chỉ tác động đến flow **reserve stock**, không ảnh hưởng payment hay shipping.

### 5. Metrics theo dõi

| Metric | Công cụ | Mục đích |
|---|---|---|
| Reserve stock success rate | Prometheus / Grafana | Đo tỷ lệ thành công |
| API latency (p50, p95, p99) | Prometheus | Phát hiện tăng latency |
| Retry count | Application logs | Xác nhận retry hoạt động |
| Circuit breaker state | Micrometer / Resilience4j | Xác nhận CB mở khi cần |
| Error rate (5xx) | Grafana dashboard | Phát hiện lỗi vượt ngưỡng |
| CPU / Memory / Thread pool | Prometheus | Phát hiện resource exhaustion |
| Alert firing | AlertManager | Kiểm tra alert có trigger đúng |

### 6. Abort Condition

- Reserve stock error rate > 2% → **dừng ngay**.
- API p95 latency > 2s → **dừng ngay**.
- Circuit breaker không tự recover sau 60s → **dừng ngay**.
- Bất kỳ downstream service nào khác bị ảnh hưởng → **dừng ngay**.

### 7. Rollback Procedure

1. Khôi phục Inventory Service instance đã bị kill (Kubernetes tự restart hoặc manual scale up).
2. Xác nhận tất cả instance healthy qua health check endpoint.
3. Verify các "pending reservation" được xử lý lại (retry hoặc reconciliation job).
4. Kiểm tra metrics trở về steady state.
5. Review alert — tắt các alert false positive nếu có.

### 8. Expected Result

- Order Service **tự retry** request sang instance Inventory Service còn lại.
- Error rate tăng nhẹ (< 1%) trong vài giây đầu, sau đó ổn định.
- Circuit breaker **mở** nếu instance down kéo dài, chuyển sang fallback "pending reservation".
- Latency p95 tăng nhẹ (do retry) nhưng vẫn < 1s.
- Alert **được trigger** đúng cho Inventory Service instance down.
- Sau khi rollback, hệ thống trở về steady state trong < 2 phút.

---

## Experiment Canvas

| # | Hạng mục | Chi tiết |
|---|---|---|
| 1 | **Target** | Inventory Service (1/3 instances) |
| 2 | **Steady State** | Success rate ≥ 99.5%, p95 < 300ms |
| 3 | **Hypothesis** | Order Service vẫn hoạt động với retry + fallback, error rate < 1% |
| 4 | **Fault Type** | Instance failure (kill pod) |
| 5 | **Blast Radius** | 1 instance, staging only, reserve stock flow only |
| 6 | **Duration** | 5 phút |
| 7 | **Abort If** | Error rate > 2% hoặc p95 > 2s hoặc CB không recover sau 60s |
| 8 | **Rollback** | Restart instance → verify health → reconcile pending → confirm steady state |
| 9 | **Observability** | Prometheus, Grafana, application logs, AlertManager |
| 10 | **Environment** | Staging (chạy staging trước, production sau khi confident) |

---

## Checklist

- [x] Có hypothesis rõ ràng
- [x] Không chạy experiment khi không có observability
- [x] Có blast radius nhỏ
- [x] Có abort condition
- [x] Có rollback plan
- [x] Hiểu chaos experiment cần bắt đầu ở staging/test environment
