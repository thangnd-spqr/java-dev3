# 08 — API Style Decision: REST vs GraphQL vs gRPC (Payroll & HR SaaS)


## 1. Khung so sánh kỹ thuật & vận hành

| Tiêu chí | REST (HTTP/JSON) | GraphQL (HTTP POST) | gRPC (HTTP/2 + Protobuf) |
| --- | --- | --- | --- |
| **Mô hình** | **Resource-oriented** (nouns + HTTP verbs + status) | **Graph-oriented** (client chọn fields trên schema) | **Contract/RPC-oriented** (binary `.proto`) |
| **Consumer phù hợp** | Public API, partner ERP, web/mobile chuẩn, webhook | BFF cho UI/Mobile ghép dữ liệu đa nguồn | Internal M2M, polyglot, low-latency/high-throughput |
| **Over/Under-fetching** | Xảy ra nếu CRUD 1-1 bảng DB; xử lý bằng BFF/`?fields=` | Giải quyết tại client (chỉ lấy đúng field cần) | Theo message contract cố định của từng RPC |
| **Pagination & Filter** | Query params: cursor (`?after=&limit=`), `?status=` | Relay Connections (`edges/node/pageInfo`) + input filter | Tường minh trong `Request` (`page_token`, `page_size`) |
| **Error model** | HTTP `4xx/5xx` + **RFC 9457 Problem Details** | HTTP `200 OK` + `errors[].extensions.code` (partial data) | **gRPC status codes** (`3`, `5`, `7`, `14`...) + `google.rpc.Status` |
| **HTTP Caching** | **Mạnh & tự nhiên** (`GET` + `ETag`, `Cache-Control`, CDN) | **Khó ở tầng HTTP** (`POST`); cần APQ + GET hoặc client cache | Không dùng HTTP cache; cache ở app/client layer |
| **Streaming** | **SSE (`text/event-stream`)** 1 chiều rất tốt cho browser | **Subscriptions** (WebSocket / SSE) | **Mạnh nhất**: Server / Client / Bidirectional streaming |
| **Rủi ro đặc thù** | Chatty API (nhiều round-trip) nếu thiếu BFF | **N+1 query**, deep query DoS, **quên AuthZ ở field/edge** | Cần gRPC-Web cho browser; **bẫy L4 LB** trên HTTP/2; khó đọc thô |

## 2. Kiến trúc tổng thể & Quyết định cho 4 bài toán (A, B, C, D)

`Mobile (A)` ──HTTPS Persisted GraphQL──▶ `Mobile BFF` ──gRPC──▶ `employee-svc` / `payroll-svc` / `leave-svc`
`HR Browser (C)` ──REST + SSE (`text/event-stream`)──▶ `Web BFF` ──gRPC──▶ `payroll-svc` ══gRPC/mTLS (B)══▶ `tax-svc`
`Enterprise ERP (D)` ──HTTPS Public REST v1 (OpenAPI)──▶ `API Gateway` ──gRPC──▶ `employee-svc` / `payroll-svc`

### **A. Mobile app xem Employee Profile + Salary + Leave Balance**
- **API Style**: **GraphQL tại tầng Mobile BFF** (ghép dữ liệu từ `employee-service`, `payroll-service`, `leave-service`).
- **Lý do**: Mạng 4G/5G có RTT cao; 1 query lấy đúng các field màn hình Home/Detail cần trong **1 round-trip**. Nếu `leave-service` lỗi/timeout, GraphQL vẫn trả được **partial data** (`profile` + `salary`) thay vì hỏng cả màn hình.
- **Security model (Object & Field-level AuthZ + DoS protection)**:
  - **AuthN**: OAuth2 PKCE → Bearer JWT ngắn hạn (5–10 phút).
  - **Object-level AuthZ**: Resolver `employee(id)` kiểm tra `canViewEmployee(jwt.sub, jwt.tenantId, id)` (nhân viên chỉ xem `id == sub`; Manager xem cấp dưới trực tiếp).
  - **Field-level AuthZ (`@auth` directive / Field Authorizer)**: Kiểm tra quyền ở URL `/graphql` là vô nghĩa. Field nhạy cảm `Employee.salary { baseAmount, bankAccount }` chỉ resolve khi `id == jwt.sub` (`salary:read:self`) **hoặc** role `HR_MANAGER`/`PAYROLL_ADMIN`. Nếu Line Manager query `{ directReports { fullName salary { baseAmount } } }`, `fullName` trả bình thường còn `salary` trả `null` + lỗi `FORBIDDEN_FIELD` trong `errors[]`. Quy tắc này áp cho cả truy vấn vòng (`department -> employees -> salary`).
  - **Chống N+1 & DoS**: Dùng **DataLoader** gom batch theo `(tenantId, employeeId)` **scope theo từng request** (cấm share cache giữa các request). Tắt Introspection ở prod; bắt buộc **Persisted Queries (allowlist queryId)**; giới hạn **Max Depth ≤ 5**, **Max Complexity/Cost ≤ 200**, page size `first ≤ 50`.
- **Versioning / Evolution**: Không đổi URL (`/graphql`), tiến hoá **additive** (chỉ thêm field/type). Đánh dấu `@deprecated(reason: ...)` và theo dõi telemetry theo `X-Client-Version`; chỉ xoá field khi lượng gọi = `0%` trong 30 ngày.
- **Observability**: Không nhìn HTTP `200` để báo xanh. Đo `graphql_request_duration_seconds{operation_name}`, `graphql_errors_total{operation_name, error_code, field_path}`, `graphql_complexity`. Bắt buộc query có `operationName`; OTel span cho request + mỗi DataLoader batch.
- **Trade-off**: Giảm round-trip và payload cho mobile, hỗ trợ partial data; đổi lại mất HTTP GET cache tự nhiên và phải quản trị chặt resolver AuthZ + DataLoader + query cost.

### **B. Internal Payroll Service gọi Tax Calculation Service**
- **API Style**: **gRPC (HTTP/2 + Protobuf)** — Unary RPC cho tính đơn lẻ & **Streaming / Batched RPC** khi chạy kỳ lương hàng loạt.
- **Lý do**: Giao tiếp M2M nội bộ trong cùng K8s/VPC. Khi chốt bảng lương 10k–100k nhân viên, cần **throughput cao, p99 latency thấp, CPU serialize tối thiểu**. Tiền và thuế cần **typed contract nghiêm ngặt** (enum, số nguyên đơn vị nhỏ nhất / `Decimal` chuẩn) và codegen tự động. HTTP/2 hỗ trợ multiplexing, **deadline/timeout propagation** và cancellation.
- **Security model**: **mTLS** bắt buộc với định danh workload **SPIFFE ID** (`payroll-service` là caller duy nhất được gọi `CalculateTax`). Truyền `tenant_id`, `actor_sub`, `payroll_run_id` qua gRPC metadata đã xác thực. Không expose ra cổng internet.
- **Versioning / Evolution**: Quản lý `.proto` bằng **Buf (`buf breaking` trong CI)**. **Cấm** đổi kiểu hoặc tái sử dụng field tag number (dùng `reserved 4, 8 to 10; reserved "old_name";`). Thay đổi tương thích ngược chỉ thêm optional field với tag mới; nếu breaking thì phát hành song song package `payroll.tax.v2.TaxCalculationService`.
- **Observability**: OTel gRPC interceptor truyền `traceparent`; metric `grpc_server_handling_seconds_bucket{grpc_method, grpc_code}` (`OK`, `INVALID_ARGUMENT`, `DEADLINE_EXCEEDED`, `UNAVAILABLE`); bật **gRPC Health Checking Protocol** cho K8s probe và Server Reflection ở staging.
- **Trade-off**: Nhanh, an toàn kiểu lúc compile, hỗ trợ streaming/deadline; đổi lại bản tin nhị phân khó đọc thô và dính **bẫy L4 Load Balancing** của K8s Service mặc định (HTTP/2 giữ 1 TCP connection làm dồn tải vào 1 pod → **bắt buộc dùng L7 LB qua Envoy/Mesh** hoặc client-side round-robin với headless service).

### **C. HR Dashboard Realtime hiển thị Payroll Processing Progress**
- **API Style**: **REST (khởi tạo & lấy snapshot) + Server-Sent Events (`SSE` — `text/event-stream`)**.
  - `POST /api/v1/payroll-runs` (`202 Accepted`) → `GET /api/v1/payroll-runs/{runId}` (snapshot) + `GET /api/v1/payroll-runs/{runId}/progress-stream` (SSE).
- **Lý do**: Consumer là trình duyệt web và chiều dữ liệu **1 chiều (server → browser)** (`processed: 4200/10000`, `failed: 3`, `stage: TAX_CALC`). SSE chạy thuần trên HTTP/2, đi xuyên proxy/firewall dễ hơn WebSocket, có sẵn `EventSource` tự động reconnect kèm `Last-Event-ID`, không cần Envoy gRPC-Web proxy hay GraphQL Subscription cồng kềnh.
- **Security model**: Xác thực qua **Session Cookie `HttpOnly; Secure; SameSite=Strict`** tại Web BFF (tránh nhét JWT lên URL query string của `EventSource` làm lộ token trong log). Kiểm tra object-level `run.tenantId == session.tenantId` + scope `payroll:read`. Sự kiện SSE chỉ chứa con số tổng hợp tiến độ, **cấm đẩy PII/lương chi tiết**. Giới hạn ≤ 3 kết nối SSE/user.
- **Versioning / Evolution**: URI `/api/v1/...`; khung sự kiện SSE dùng `event: progress | step_changed | completed | failed`, payload JSON chỉ thêm field mới (client bỏ qua event/field lạ). Hỗ trợ `Last-Event-ID` để nối lại không mất trạng thái.
- **Observability**: Đo `sse_active_connections{tenant}`, `sse_events_sent_total{event_type}`, `sse_delivery_lag_ms`. Gửi comment heartbeat `: ping\n\n` mỗi 15s để giữ kết nối qua LB/Ingress và phát hiện kết nối chết.
- **Trade-off**: Đơn giản, chuẩn HTTP, tự reconnect; đổi lại chỉ truyền text 1 chiều, cần non-blocking I/O ở backend và cấu hình `proxy_read_timeout` riêng cho route stream. Nếu mạng khách hàng chặn long-lived HTTP → UI tự **fallback về REST polling** mỗi 5s.

### **D. Public Integration API cho khách hàng Enterprise**
- **API Style**: **REST Resource-Oriented API (HTTPS + JSON, OpenAPI 3.1) + Webhooks (ký HMAC-SHA256)**.
- **Lý do**: Khách hàng enterprise tích hợp qua SAP, Workday, MuleSoft, Boomi, .NET/Java. REST + OpenAPI có độ tương thích 100% với mọi iPaaS/SDK generator mà không bắt khách hàng cài đặt Protobuf hay học GraphQL. Chuẩn hoá: **Cursor pagination** (`?cursor=...&limit=100`), filtering (`?updatedSince=...&status=ACTIVE`), **HTTP Caching** (`ETag`/`304`), **`Idempotency-Key`** cho mọi `POST/PATCH`, và lỗi chuẩn **RFC 9457 Problem Details**.
- **Security model**: **OAuth2 Client Credentials + mTLS / `private_key_jwt`**. Cấp fine-grained scope theo nhu cầu tích hợp (`employees:read`, `attendance:write`, `payroll-journals:read` — nguyên tắc least privilege). Cô lập tenant tuyệt đối từ token (`tenant_id` + Postgres RLS, trả `404` nếu khác tenant). Rate-limit & quota theo từng `client_id`/`tenant` kèm header `RateLimit-*` và `Retry-After`.
- **Versioning / Evolution**: Chu kỳ thay đổi của ERP khách hàng rất chậm (6–18 tháng). Dùng **URI Major Versioning** (`/api/v1/...`). Thay đổi tương thích ngược (thêm field/optional param) cập nhật trong `v1` (kiểm tra tự động bằng **`oasdiff` trong CI**). Khi breaking change: mở `/api/v2/...` chạy song song, gắn header chuẩn RFC 8594 `Deprecation: true`, `Sunset: <date>`, `Link: ...; rel="deprecation"` và duy trì `v1` tối thiểu **6–12 tháng**.
- **Observability**: Trả `X-Request-Id` / `traceparent` trong mọi header và body lỗi RFC 9457 để đối soát ticket hỗ trợ. Metric theo từng tenant (`http_requests_total{tenant, route, status}`, `429_total`, latency `p99`) và cung cấp Developer Portal để khách hàng tự tra cứu log tích hợp/webhook (đã mask PII).
- **Trade-off**: Dễ tích hợp nhất, tận dụng toàn bộ hệ sinh thái HTTP (WAF, CDN, rate-limit, OpenAPI), bảo mật rõ ràng theo endpoint; đổi lại JSON nặng hơn Protobuf và kém linh hoạt hơn GraphQL (bù lại tải truy vấn hoàn toàn dự đoán được).

## 3. Tóm tắt quyết định & Hoàn thành

| Case | Style chọn | Lý do quyết định | Trọng tâm Security & Evolution |
| --- | --- | --- | --- |
| **A. Mobile App** | **GraphQL (BFF)** | 1 round-trip mạng di động, chọn đúng field, partial data | **Object + Field-level AuthZ (`Salary`)**, DataLoader per-request, Persisted Queries, depth/cost limit; `@deprecated` + field telemetry |
| **B. Payroll → Tax** | **gRPC (Protobuf)** | Internal M2M, batch tính thuế cần p99 thấp & typed contract | **mTLS (SPIFFE)**, metadata `tenant_id`; `buf breaking` CI, `reserved` field tags, L7 load balancing |
| **C. HR Progress** | **REST + SSE** | Stream 1 chiều server→browser, tự reconnect, nhẹ hơn WS | Cookie `HttpOnly` qua BFF, không phát PII, giới hạn kết nối/user; `Last-Event-ID` + fallback REST polling |
| **D. Enterprise API** | **REST + Webhook** | Chuẩn mực cho ERP/iPaaS, cursor page, ETag, RFC 9457 | OAuth2 Client Credentials + mTLS, scope tối thiểu, rate-limit/tenant; `/api/v1` + `oasdiff` + `Sunset` 6–12 tháng |

- [x] Không trả lời cảm tính “GraphQL tốt hơn REST” hay “gRPC luôn nhanh hơn”; mọi lựa chọn gắn với trade-off vận hành.
- [x] Chọn theo **consumer**, **workload** và **operational constraint** (L7 LB cho gRPC, timeout/fallback cho SSE, iPaaS compatibility cho Enterprise).
- [x] Có chiến lược **API evolution/versioning** cụ thể cho cả 4 trường hợp.
- [x] Xét đầy đủ **authorization ở cả Object-level và Field-level** (kèm N+1 DataLoader & query complexity) khi dùng GraphQL ở Case A.
