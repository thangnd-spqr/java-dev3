# 07 — Security by Design: OAuth2, JWT, OWASP & Threat Modeling (Payroll SaaS)

> Câu hỏi sai: "API có JWT chưa?". Câu hỏi đúng: **token do ai phát hành, API verify gì, scope nào cho API nào,
> token lộ thì thiệt hại tới đâu, ai làm gì với lương của ai thì có dấu vết không.**

## 1. Phạm vi, asset & trust boundary

`Browser/Mobile (BFF)` ──TLS──▶ `API Gateway` ──mTLS──▶ `payroll-service` ──▶ `PostgreSQL` / `Kafka` / `KMS`
`Authorization Server (Keycloak/IdP)` phát hành token; `payroll-service` là **resource server**.

| Asset | Endpoint liên quan | Mức nhạy cảm | Hậu quả nếu mất |
| --- | --- | --- | --- |
| A1. Lương, phụ cấp, số TK ngân hàng | `GET/PUT /employees/{id}/salary` | **Rất cao** (PII + tài chính) | Lộ lương toàn công ty, sửa lương để chiếm tiền |
| A2. Payslip (lương, thuế, BHXH, MST) | `GET /payslips/{id}` | Rất cao | Lộ thông tin cá nhân, vi phạm NĐ 13/2023 |
| A3. Payroll run (lệnh tính & chi lương) | `POST /payroll-runs` | Cao (tác động tiền thật) | Chạy trùng/sai kỳ, DoS tài nguyên tính toán |
| A4. Token, signing key, DB credential | Mọi endpoint | Critical | Giả mạo bất kỳ user/tenant nào |
| A5. Audit log | Mọi thao tác ghi/đọc nhạy cảm | Cao | Mất khả năng điều tra, chối bỏ hành vi |

## 2. Authentication ≠ Authorization

| | Authentication (AuthN) | Authorization (AuthZ) |
| --- | --- | --- |
| Trả lời | **Bạn là ai?** | **Bạn được làm gì, trên tài nguyên nào?** |
| Ở đâu | Authorization Server (login, MFA) → phát token | Resource server: scope **+** role **+** quan hệ dữ liệu (tenant, owner, phòng ban) |
| Sai thì | 401 Unauthorized | 403 Forbidden (hoặc 404 để không lộ sự tồn tại) |

JWT hợp lệ chỉ chứng minh **AuthN**. Token có `scope=salary:read` **chưa đủ** để đọc lương của `{id}` bất kỳ —
vẫn phải check object-level (xem T1). OAuth2 roles: *resource owner* = nhân viên/HR; *client* = BFF web;
*authorization server* = IdP; *resource server* = payroll-service.

## 3. Token design & validation

| | Access token | Refresh token |
| --- | --- | --- |
| Dạng | JWT ký **RS256/ES256** (JWS, **không mã hoá**) | Opaque, lưu DB phía AS (hash) |
| TTL | 5–10 phút | 8h idle / 7 ngày tối đa, **rotation** mỗi lần dùng + reuse detection |
| Lưu ở | Bộ nhớ BFF server-side; **không** `localStorage` | Cookie `HttpOnly; Secure; SameSite=Strict` ở BFF, không bao giờ xuống JS |

```json
{ "iss": "https://idp.payroll.vn/realms/prod", "aud": "payroll-api", "sub": "u-8f2c",
  "exp": 1792400000, "iat": 1792399400, "jti": "c1a9…", "tenant_id": "t-42",
  "scope": "salary:read payslip:read:self", "roles": ["HR_MANAGER"], "amr": ["pwd","otp"] }
```

> [!WARNING]
> JWT chỉ **base64url**, ai cầm token đều đọc được payload. **Cấm** đặt lương, số TK, CCCD, email cá nhân,
> danh sách employeeId được phép vào claim. Chỉ đặt định danh và quyền thô; dữ liệu nhạy cảm tra ở server.

**Resource server bắt buộc verify (Spring `oauth2ResourceServer().jwt()` + validator tùy chỉnh):**
1. **Signature** qua JWKS của `iss` (cache, xoay `kid`); allowlist `alg` = RS256/ES256, từ chối `none`/HS*.
2. **`iss`** khớp chính xác realm prod (không chấp nhận token realm staging).
3. **`aud`** chứa `payroll-api` — chặn token phát cho service khác bị đem dùng lại.
4. **`exp`/`nbf`/`iat`** với clock skew ≤ 30s; **`tenant_id`** khớp tenant của tài nguyên.
5. **Scope → endpoint** (deny-by-default, endpoint không khai báo = 403):

| Endpoint | Scope | Rule bổ sung (object-level) |
| --- | --- | --- |
| `POST /payroll-runs` | `payroll:run` | Role `PAYROLL_ADMIN`, `amr` có MFA, tenant khớp, idempotency key |
| `GET /employees/{id}/salary` | `salary:read` | HR cùng tenant **và** trong org-scope; hoặc `sub` = chính nhân viên đó |
| `PUT /employees/{id}/salary` | `salary:write` | HR_MANAGER, MFA ≤ 15 phút (step-up), **không tự sửa lương mình**, maker-checker > 20% |
| `GET /payslips/{id}` | `payslip:read:self` / `payslip:read:all` | Self: `payslip.employeeId == sub`; All: HR cùng tenant; run phải `FINALIZED` |

## 4. Threat → Attack path → Mitigation → Detection

| # | Threat (OWASP 2025 / STRIDE) | Asset · Endpoint | Attack path | Mitigation | Detection |
| --- | --- | --- | --- | --- | --- |
| T1 | **IDOR / Broken Access Control** (A01 · E, I) | A1, A2 · `GET /employees/{id}/salary`, `GET /payslips/{id}` | Nhân viên đăng nhập hợp lệ, đổi `{id}` tuần tự `1001→1002…` hoặc đổi `payslipId` của đồng nghiệp | ID dạng UUIDv7 (giảm đoán, **không** thay AuthZ); query luôn kèm `WHERE tenant_id=:jwtTenant AND …`; policy check `canRead(sub, employee)` ở service layer; trả 404 khi không thuộc quyền; Postgres RLS theo `tenant_id` làm lớp 2 | Alert khi 1 `sub` nhận ≥ 5 lần 403/404 trên `/salary` trong 5 phút hoặc đọc > N employee khác nhau/giờ; test tự động "user A đọc tài nguyên user B" trong CI |
| T2 | **Privilege escalation qua PUT** (A01 · E, T) | A1 · `PUT /employees/{id}/salary` | HR tự tăng lương mình; hoặc mass-assignment: gửi thêm `"bankAccount"`, `"tenantId"` trong body | DTO allowlist riêng (`SalaryUpdateRequest{amount, effectiveDate, reason}`), bỏ field lạ; chặn `id == sub`; maker-checker cho thay đổi lớn; `If-Match` version chống ghi đè | Audit event `SalaryChanged{before, after, actor, approver}`; alert khi đổi số TK ngân hàng + lương trong cùng 24h, hoặc actor = subject |
| T3 | **Token theft / replay** (A07 · S) | A4 · tất cả | XSS đọc token từ `localStorage`; token lọt vào log/URL/Referer; refresh token bị đánh cắp | Token chỉ ở BFF; CSP chặt; access TTL 5–10 phút; refresh rotation + **reuse detection** (dùng lại RT cũ → thu hồi cả family); không log header `Authorization`; DPoP/mTLS cho client M2M | Cảnh báo refresh-token reuse; cùng `jti`/`sub` từ 2 ASN/quốc gia trong 10 phút; token dùng sau khi logout |
| T4 | **Forged / misused token** (A07, A04 · S) | A4 · tất cả | Token `alg:none`, ký HS256 bằng public key, token staging (`iss` khác) hoặc token cho `report-api` (`aud` khác) | Validation 5 bước mục 3; JWKS chỉ lấy từ `iss` cấu hình cứng, không từ header `jku`/`x5u` | Metric `jwt_validation_failed{reason}`; spike `invalid_aud`/`invalid_signature` → page on-call |
| T5 | **Injection** (A05 · T, I) | A1, A3 · `PUT …/salary`, `POST /payroll-runs`, query filter | `period="2026-10' OR 1=1--"`; `sort=` ghép vào ORDER BY; công thức phụ cấp đánh giá bằng SpEL/script | JPA/JDBC tham số hoá 100%; `sort` theo allowlist cột; Bean Validation (`@Pattern("\\d{4}-\\d{2}")`, `@DecimalMax`); không eval biểu thức người dùng; DB user ít quyền (no DDL) | WAF rule SQLi log-only + block; SAST (Semgrep/SpotBugs) trong CI; alert lỗi SQL syntax từ DB |
| T6 | **Sensitive data exposure** (A04 Cryptographic Failures · I) | A1, A2 · GET salary, GET payslip | Lương/số TK xuất hiện trong log, APM trace, error stack, cache CDN, JWT, response thừa field | Mã hoá cột số TK & lương (envelope encryption, key ở KMS); response DTO tối thiểu, mask số TK `****1234`; `Cache-Control: no-store`; log masking filter; TLS 1.2+ | Log scanner tìm pattern số TK/lương; DLP trên bucket export; review OpenAPI diff thêm field nhạy cảm |
| T7 | **Thiếu audit/logging** (A09 · R) | A5 · tất cả | HR sửa lương rồi chối; người đọc trộm lương không để dấu vết; log bị xoá | Audit **cả đọc** salary/payslip: `who, tenant, action, resourceId, result, ip, traceId`; append-only (WORM/S3 Object Lock), tách quyền với app; giữ ≥ 5 năm | Alert khi audit pipeline ngừng nhận event > 5 phút; báo cáo hàng tuần "ai đã xem lương ai" cho Data Owner |
| T8 | **Rate-limit abuse / DoS** (A06 Insecure Design · D) | A3, A1 · `POST /payroll-runs`, GET salary | Spam tạo run làm cạn worker; script cào lương với token hợp lệ; brute-force `payslipId` | Rate limit theo `sub`+`tenant` ở gateway (salary GET: 60/phút; payroll-runs: 5/giờ/tenant); `Idempotency-Key` + unique `(tenant, period, status=RUNNING)`; queue có quota/tenant | Metric `429_total{route,sub}`; alert run trùng kỳ bị chặn; đọc salary vượt baseline 3σ |
| T9 | **Security misconfiguration** (A02 · I, E) | A4 · hạ tầng | Actuator `/env`, `/heapdump` public; CORS `*` + credentials; Swagger bật ở prod; secret trong `application.yml`/Git | `management.endpoints` chỉ `health,prometheus` trên port nội bộ; CORS allowlist origin; secret từ Vault/KMS, xoay định kỳ, không commit; security headers mặc định; image non-root | Scan cấu hình (Checkov, `gitleaks` trong CI); external probe định kỳ tới `/actuator/*`, `/swagger-ui`; alert truy cập Vault bất thường |

## 5. Blast radius khi token lộ

| Token lộ | Phạm vi | Giới hạn bằng |
| --- | --- | --- |
| Access token nhân viên | Chỉ payslip **của chính họ**, ≤ 10 phút | TTL ngắn + scope `:self` + object check |
| Access token HR_MANAGER | Đọc/sửa lương 1 tenant, ≤ 10 phút | `PUT` cần step-up MFA mới (`auth_time`), maker-checker, audit |
| Refresh token | Phát access token mới tới khi bị thu hồi | Rotation + reuse detection, bind với BFF session, revoke khi logout |
| Signing key IdP | **Toàn hệ thống** | Key trong HSM/KMS, xoay `kid`, runbook revoke JWKS < 15 phút |

## 6. Secure-by-default API rules

- **Deny by default**: endpoint mới không có `@PreAuthorize`/policy → test kiến trúc (ArchUnit) fail build.
- Tenant lấy từ token, **không bao giờ** từ path/body/header client gửi.
- Response DTO riêng, không trả entity; lỗi theo RFC 9457, không lộ stack trace.
- Mọi thay đổi tiền: idempotency, optimistic lock, audit before/after, step-up MFA.
- Threat model này được review lại khi thêm endpoint, đổi luồng auth, hoặc thêm bên thứ ba nhận dữ liệu lương.

## 7. Hoàn thành

- [x] Phân biệt authentication và authorization (mục 2, T1).
- [x] Không đặt sensitive data vào JWT payload, ghi rõ JWT chỉ ký, không mã hoá (mục 3).
- [x] Có issuer/audience/expiry/scope validation + scope map theo endpoint (mục 3).
- [x] Mỗi threat có attack path → mitigation → detection cụ thể theo endpoint (mục 4).

