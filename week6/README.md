# Week 6 — Software Design & Microservice Architecture: Multi-Tenant Payroll SaaS

Thiết kế kiến trúc hệ thống **HR & Payroll SaaS đa tenant** đáp ứng quy mô **50,000 tenants** và **5,000,000 employees**.

---

## 1. Bài toán & Yêu cầu Nghiệp vụ (Business Requirements)

- **Quy mô**: **50,000 tenants**, **5,000,000 employees** (Enterprise tenant có thể lên tới **100,000 employees**).
- **Nghiệp vụ chính**: HR quản lý **Employee**, **Leave**, **Salary**; chạy **Payroll hàng tháng**.
- **Hiệu năng đọc**: Nhân viên xem **Payslip trong < 2 giây** (`p99 < 2s`, mục tiêu nội bộ `p95 < 300ms`).
- **Xử lý bất đồng bộ**: **Payroll calculation chạy async** theo chunk; gửi **Notification** sau khi payslip sẵn sàng.
- **Độ tin cậy & Chính xác**: **Tuyệt đối không duplicate payroll run**; không mất hoặc nhân đôi phiếu lương.
- **Tuân thủ (Compliance)**: **Audit salary/payroll lưu trữ 7 năm** (append-only, WORM).
- **Bảo mật đa tenant**: **Admin của tenant A không được xem hoặc tác động dữ liệu của tenant B** (cô lập 4 lớp từ JWT tới PostgreSQL RLS).

---

## 2. Cấu trúc Deliverables (`week6/`)

```text
week6/
├── README.md                              # Tổng quan bài toán, danh mục tài liệu & checklist DoD
├── 01-architecture-tradeoff.md            # So sánh Monolith vs Modular Monolith vs Microservices & lộ trình tách
├── 02-ddd-boundary.md                     # Subdomains, Bounded Contexts, Aggregates, Invariants & Data Ownership
├── 03-event-driven-design.md              # Command vs Event, Transactional Outbox, Idempotent Consumer, Retry & DLQ
├── 04-system-design-process.md            # FR/NFR, Capacity Estimation (5M emp, 7 năm), Components, Data Flow & Failures
├── 05-caching-design.md                   # Chiến lược Cache (Redis/Caffeine), Key Design, Pre-warm, Invalidation & Stampede
├── 06-nfr-observability.md                # 5 SLIs, 5 SLOs + Error Budget, Distributed Tracing (Span Links) & 5 Alerts
├── 07-security-threat-model.md            # OAuth2/OIDC + BFF, JWT Validation, RBAC/ABAC/RLS & STRIDE/OWASP Threat Model
├── 08-api-style-decision.md               # Quyết định REST vs GraphQL vs gRPC vs SSE cho 4 bài toán thực tế
├── 09-modernization-plan.md               # Kế hoạch hiện đại hóa Legacy (Strangler Fig, Golden Master, CDC & Rollback)
├── capstone-architecture.md               # Tài liệu tổng hợp kiến trúc đầu-cuối (End-to-End Capstone Architecture)
└── diagrams/
    ├── context-map.png                    # Sơ đồ DDD Bounded Contexts & Upstream/Downstream Relationships
    ├── system-context.png                 # Sơ đồ C4 Level 1 — System Context (Actors & External Systems)
    ├── container-diagram.png              # Sơ đồ C4 Level 2 — Container Architecture (BFF, Services, DB, Queue, Cache)
    ├── event-flow.png                     # Luồng chạy Payroll bất đồng bộ, Outbox, Chunk Worker, Retry & DLQ
    ├── security-flow.png                  # Luồng xác thực OAuth2/BFF & Cô lập đa tenant 4 lớp (JWT → ABAC → RLS)
    ├── cache-flow.png                     # Luồng đọc/ghi Cache Payslip (< 2s), Pre-warm, Single-Flight & Invalidation
    ├── deployment-and-observability.png   # Hạ tầng Multi-AZ, Tracing xuyên chuỗi Async & Hệ thống Cảnh báo SLO
    └── migration-plan.png                 # Lộ trình chuyển đổi Strangler Fig (Shadow Run, Canary & Reverse CDC)
```

---

## 3. Danh mục Tài liệu Chi tiết

| STT | Tài liệu | Nội dung trọng tâm |
| --- | --- | --- |
| **01** | [01-architecture-tradeoff.md](01-architecture-tradeoff.md) | Phân tích chi phí vận hành Microservices; lý do khởi đầu bằng **Modular Monolith** và tách có chọn lọc (**Notification → Reporting → Payroll**). |
| **02** | [02-ddd-boundary.md](02-ddd-boundary.md) | Phân loại Core (`Payroll`), Supporting (`Leave`, `Employee`), Generic (`Identity`, `Notification`); thiết kế Aggregate Root bảo vệ Invariant và quy tắc 1 bảng 1 service ghi. |
| **03** | [03-event-driven-design.md](03-event-driven-design.md) | Thiết kế lệnh `RunPayroll` và sự kiện `PayrollCompleted`; mẫu **Transactional Outbox**, khử trùng tại Consumer, **Exponential Backoff Retry** và **DLQ Replay**. |
| **04** | [04-system-design-process.md](04-system-design-process.md) | Ước lượng tải (2,100 payslips/s write burst, 350 RPS read peak, 42 TB PDF + 3 TB DB + 4.2 TB WORM Audit trong 7 năm); thiết kế **Chunked Fair Scheduling** cho tenant 100k nhân viên. |
| **05** | [05-caching-design.md](05-caching-design.md) | Tiêu chí 3 điều kiện để cache; key có prefix `{tenantId}` và `runId` bất biến; **Pre-warming + Single-flight** chống Cache Stampede sáng ngày trả lương. |
| **06** | [06-nfr-observability.md](06-nfr-observability.md) | Định nghĩa 5 SLIs/SLOs, chính sách Error Budget, truyền `correlationId` + W3C `traceparent` qua Outbox/Kafka, kiểm soát cardinality và 5 Alerts có Runbook. |
| **07** | [07-security-threat-model.md](07-security-threat-model.md) | Thiết kế BFF + JWT RS256 ngắn hạn; phân quyền Object/Field-level; cô lập tenant tới tận **PostgreSQL RLS**; phân tích 9 mối đe dọa theo OWASP/STRIDE. |
| **08** | [08-api-style-decision.md](08-api-style-decision.md) | **GraphQL** cho Mobile BFF, **gRPC (Protobuf + mTLS)** cho Payroll ↔ Tax, **REST + SSE** cho tiến độ chạy lương, **REST OpenAPI + Webhook** cho Enterprise ERP. |
| **09** | [09-modernization-plan.md](09-modernization-plan.md) | Hiện đại hóa hệ thống cũ không Big-Bang: dựng **Golden Master Tests**, cắt dần qua Strangler Facade, chạy **Shadow Comparator** (`diff = 0`) và **Reverse CDC Rollback**. |
| **10** | [**capstone-architecture.md**](capstone-architecture.md) | **Bản thiết kế tổng thể (Capstone)** kết nối toàn bộ 9 chuyên đề kèm bảng tổng hợp **9 Architecture Decision Records (ADRs) & Trade-offs**. |

---

## 4. Tổng hợp Quyết định Kiến trúc & Trade-off Cốt lõi

| Vấn đề | Quyết định | Đánh đổi (Trade-off) |
| --- | --- | --- |
| **Cấu trúc hệ thống** | Modular Monolith (`Employee`, `Leave`, `Identity`) + Tách riêng `Payroll`, `Tax`, `Notification`, `Audit/Reporting` | Tránh phức tạp của Distributed Monolith cho các module ổn định; chấp nhận duy trì hạ tầng lai (monolith + một số services). |
| **Tenant 100k nhân viên** | Chia run thành **Chunk 1,000 emp** + **Weighted Fair Queue** (giới hạn in-flight chunks/tenant) | Thêm tầng Orchestrator quản lý trạng thái chunk; đổi lại tenant 100k xong trong ~2 phút mà không làm nghẽn 49,999 tenant nhỏ. |
| **Chống Duplicate Run** | `Idempotency-Key` + **DB Unique Partial Index** `(tenant_id, pay_group_id, period) WHERE status <> 'CANCELLED'` | Gắn chặt ràng buộc vào PostgreSQL; đổi lại đảm bảo **tuyệt đối 0 duplicate** kể cả khi Redis/mạng lỗi. |
| **Xem Payslip < 2s** | Tách Read Replica + **Redis Cache-aside Pre-warmed** theo key bất biến `pay:v1:{tenantId}:payslip:{runId}:{empId}` | Tốn RAM Redis trong vài ngày đầu sau kỳ lương; đổi lại `p95 < 300ms`, miễn nhiễm với write burst và không bao giờ bị stale nội dung. |
| **Cô lập Đa Tenant** | Shared DB + **4 lớp bảo vệ**: `tenant_id` từ JWT → ABAC Service → Cache Prefix → **PostgreSQL RLS** | Phải quản lý session context `app.current_tenant_id` ở mọi kết nối DB; đổi lại chi phí vận hành 50k tenants thấp và an toàn tuyệt đối. |
| **Lưu trữ Audit 7 năm** | Ghi qua **Transactional Outbox** sang kho **Append-only WORM** (PostgreSQL Partition + S3 Object Lock) | Độ trễ hiển thị log audit vài giây (eventual consistency); đổi lại cam kết **Audit loss = 0** và không làm phình DB OLTP. |

---

## 5. Definition of Done

- [x] Có business requirement và NFR rõ ([04-system-design-process.md](04-system-design-process.md), [capstone-architecture.md](capstone-architecture.md#1-business-requirements-nfr--capacity-estimation))
- [x] Có bounded contexts và ownership ([02-ddd-boundary.md](02-ddd-boundary.md), [diagrams/context-map.png](diagrams/context-map.png))
- [x] Có event flow và async failure strategy ([03-event-driven-design.md](03-event-driven-design.md), [diagrams/event-flow.png](diagrams/event-flow.png))
- [x] Có cache strategy ([05-caching-design.md](05-caching-design.md), [diagrams/cache-flow.png](diagrams/cache-flow.png))
- [x] Có security/threat model ([07-security-threat-model.md](07-security-threat-model.md), [diagrams/security-flow.png](diagrams/security-flow.png))
- [x] Có API style decision ([08-api-style-decision.md](08-api-style-decision.md))
- [x] Có observability plan ([06-nfr-observability.md](06-nfr-observability.md), [diagrams/deployment-and-observability.png](diagrams/deployment-and-observability.png))
- [x] Có scale/failure/migration reasoning ([01-architecture-tradeoff.md](01-architecture-tradeoff.md), [04-system-design-process.md](04-system-design-process.md), [09-modernization-plan.md](09-modernization-plan.md), [diagrams/migration-plan.png](diagrams/migration-plan.png))
- [x] Mỗi quyết định có trade-off ([capstone-architecture.md](capstone-architecture.md#10-tổng-hợp-quyết-định-kiến-trúc--phân-tích-đánh-đổi-adr--trade-offs))

