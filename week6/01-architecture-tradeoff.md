# 01 — Architecture Trade-off cho HR/Payroll SaaS

## 1. So sánh 3 kiến trúc

| Tiêu chí | Monolith | Modular Monolith | Microservices |
| --- | --- | --- | --- |
| Deployment unit | 1 | 1 | N (mỗi service 1 unit) |
| Database | 1 DB dùng chung, join tự do | 1 DB, **schema riêng theo module** | DB riêng theo service |
| Ownership | Cả team chung code | Team sở hữu module | Team sở hữu service end-to-end |
| Coupling | Cao, dễ "big ball of mud" | Thấp, giao tiếp qua API nội bộ / event | Thấp nhất, nhưng qua network |
| Transaction | ACID local | ACID local | Saga / outbox / eventual consistency |
| Chi phí vận hành | Thấp | Thấp | **Cao** (CI/CD, tracing, on-call, infra) |

> **Coupling** thấp + **cohesion** cao là mục tiêu. Microservices chỉ là *một cách* để đạt điều đó,
> modular monolith đạt được phần lớn lợi ích mà không trả giá distributed system.

## 2. Hiện trạng & vấn đề

- 1 Spring Boot app + 1 PostgreSQL, 20 dev → bắt đầu dẫm chân nhau khi merge/deploy.
- **Payroll** thay đổi thường xuyên → mỗi lần sửa Payroll phải deploy cả hệ thống.
- **Reporting** query nặng → tranh tài nguyên DB với nghiệp vụ OLTP (chấm công, tính lương).
- **Notification** cần scale theo số email → scale cả app chỉ để gửi mail là lãng phí.

## 3. Đề xuất: **Modular Monolith trước, tách có chọn lọc sau**

Không tách toàn bộ thành microservices. Lý do:
- 20 dev ≈ 3–4 team → không đủ người để own 6 service + toàn bộ hạ tầng vận hành.
- Boundary giữa Employee / Leave / Payroll **chưa chắc đã ổn định** (Payroll đọc Employee, Leave liên tục).
- Chỉ có 2 module có lý do thật sự để tách: **Notification** (scale) và **Reporting** (tải).

### Module boundary theo business capability

| Module | Business capability | Data owner của |
| --- | --- | --- |
| Identity | Đăng nhập, user, role, tenant | `users`, `roles`, `tenants` |
| Employee | Hồ sơ nhân sự, phòng ban, hợp đồng | `employees`, `departments` |
| Leave | Đơn nghỉ phép, số ngày phép | `leave_requests`, `leave_balances` |
| Payroll | Tính lương, phụ cấp, khấu trừ, phiếu lương | `payroll_runs`, `payslips` |
| Notification | Gửi email / push | `notification_logs` |
| Reporting | Báo cáo, dashboard | Read model (không own dữ liệu gốc) |

Quy tắc: module **không** truy cập bảng của module khác; chỉ gọi qua public API (interface) hoặc event.
Có thể enforce bằng Spring Modulith / ArchUnit.

## 4. Đánh giá từng module

| Module | Business change | Deploy frequency | Scale độc lập | Data ownership | Quyết định |
| --- | --- | --- | --- | --- | --- |
| Notification | Thấp | Thấp | **Cao** (theo email volume) | Gần như không có | **Tách đầu tiên** |
| Reporting | Trung bình | Trung bình | **Cao** (query nặng) | Chỉ đọc | **Tách thứ hai** (read model) |
| Payroll | **Cao** | **Cao** | Theo đợt (cuối tháng) | Phụ thuộc Employee, Leave | Module hoá kỹ, **cân nhắc tách sau** |
| Employee | Thấp | Thấp | Thấp | Core data, nhiều module đọc | **Chưa tách** |
| Leave | Trung bình | Thấp | Thấp | Gắn chặt Employee | **Chưa tách** |
| Identity | Thấp | Thấp | Thấp | Nhạy cảm bảo mật | **Chưa tách** (hoặc dùng IdP: Keycloak/Cognito) |

**Vì sao Notification trước?** Stateless, giao tiếp async qua event (`PayslipGenerated`, `LeaveApproved`),
lỗi không làm hỏng giao dịch nghiệp vụ → rủi ro thấp nhất, là bài tập tốt để dựng hạ tầng (broker, CI/CD, tracing).

**Vì sao Payroll chưa tách ngay?** Dù đổi nhiều, Payroll cần dữ liệu Employee + Leave **nhất quán** tại thời điểm chốt lương.
Tách sớm → phải xử lý distributed transaction cho nghiệp vụ tiền bạc. Chỉ tách khi boundary ổn định và đã có snapshot/event cho dữ liệu đầu vào.

## 5. Migration từng bước (Strangler Fig, không big-bang)

| Bước | Hành động | Kết quả |
| --- | --- | --- |
| 1 | Tái cấu trúc package theo module, tách schema DB theo module, bỏ join chéo | Modular monolith, boundary rõ |
| 2 | Thêm domain event nội bộ + **transactional outbox** | Module giao tiếp lỏng, sẵn sàng tách |
| 3 | Dựng nền tảng: message broker, CI/CD per service, centralized log, tracing, metrics | Đủ observability trước khi có network call |
| 4 | Tách **Notification Service** — consume event từ broker, autoscale theo queue depth | Scale email độc lập |
| 5 | Reporting: read replica → CQRS read model (event/CDC) → **Reporting Service** riêng | OLTP không bị query nặng ảnh hưởng |
| 6 | Đánh giá lại Payroll: nếu deploy conflict vẫn cao và boundary ổn định → tách | Quyết định dựa trên dữ liệu, không cảm tính |

Mỗi bước đều deploy được, rollback được, và đo lại trước khi sang bước tiếp.

## 6. Cost vận hành của microservices

- **Hạ tầng**: N pipeline, N DB, service discovery, API gateway, message broker.
- **Observability**: bắt buộc distributed tracing, correlation ID, log tập trung, alerting theo service.
- **Failure modes mới**: timeout, retry storm, partial failure, network partition → cần circuit breaker, idempotency.
- **Data consistency**: mất ACID xuyên service → saga, compensation, eventual consistency.
- **Testing**: contract test, integration test môi trường nhiều service.
- **Con người**: on-call theo service, cognitive load cao, cần platform/DevOps skill.

## 7. Rủi ro nếu tách tất cả cùng lúc

- **Distributed monolith**: service tách nhưng vẫn gọi đồng bộ dây chuyền, phải deploy cùng nhau → tệ hơn monolith.
- **Sai boundary**: tách khi domain chưa rõ → sửa boundary giữa service rất đắt (đổi API, migrate data).
- **Payroll sai số liệu**: Employee/Leave/Payroll ở 3 DB → lương tính trên dữ liệu không nhất quán.
- **Latency & lỗi dây chuyền**: 1 request đi qua nhiều hop; 1 service chết kéo theo cả luồng.
- **Đóng băng tính năng**: team dồn sức rewrite, business đứng yên nhiều tháng.
- **Không debug được**: chưa có tracing/monitoring thì sự cố production rất khó tìm nguyên nhân.

## 8. Kết luận

> Chuyển sang **Modular Monolith** ngay; tách **Notification** rồi **Reporting** khi có lý do scale rõ ràng;
> giữ Identity, Employee, Leave trong monolith; Payroll module hoá kỹ và chỉ tách khi boundary + hạ tầng đã sẵn sàng.
> Microservices là công cụ giải quyết vấn đề scale/deploy/ownership cụ thể — **không phải mặc định tốt hơn**.

## Checklist

- [x] Không coi microservices là mặc định tốt hơn.
- [x] Phân tích được cost vận hành của microservices.
- [x] Đề xuất module boundary theo business capability.
- [x] Có migration theo từng bước, không "big-bang rewrite".

