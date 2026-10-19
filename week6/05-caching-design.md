# 05 — Caching Strategies & Cache Invalidation: Payroll SaaS

> Cache **không** phải "thêm Redis cho nhanh". Mỗi data phải trả lời được: cache gì, key gì, TTL bao lâu,
> update thì invalidate thế nào, miss hàng loạt DB có chịu nổi không, và **stale bao lâu thì chấp nhận được**.

## 1. Tiêu chí quyết định cache

Chỉ cache khi thoả **đủ cả 3**, "đọc nhiều" thôi thì chưa đủ:

1. **Đắt khi đọc**: query join nhiều bảng, tính toán, hoặc DB/replica là bottleneck thật (đo được, không đoán).
2. **Thay đổi ít hoặc bất biến** so với tần suất đọc (read/write ratio cao).
3. **Stale có giới hạn chấp nhận được** — hoặc có cơ chế invalidation đủ nhanh để stale ≈ 0.

Không cache khi: data dùng để **ra quyết định ghi** (trừ phép, duyệt lương), data thay đổi liên tục
(`payroll_run.progress`), audit log, hoặc query PK đơn giản mà DB đã trả < 5ms.

## 2. Bảng thiết kế

| Data | Cache hay không | Strategy | TTL | Invalidation | Stale acceptable? |
| --- | --- | --- | --- | --- | --- |
| **A. Employee profile** (tên, phòng ban, chức danh, avatar) | **Có** — chỉ phần hiển thị; **không** cache lương, tài khoản ngân hàng | Cache-aside (Redis) | 30 phút ± jitter 10% | `EmployeeUpdated` (outbox) → **DEL** key; TTL là lưới an toàn | **Có**, ≤ vài phút (dữ liệu hiển thị) |
| **B. Leave balance** | **Không** ở write path. Display: tuỳ chọn, cache ngắn | Đọc DB `SELECT … FOR UPDATE` khi xin/duyệt phép; display: cache-aside | Display: 60s | `LeaveApproved/Cancelled/Accrued` → DEL key | Display: có (≤ 60s). **Quyết định: KHÔNG** |
| **C. Payroll payslip** | **Có** — chỉ khi run `FINALIZED` (bất biến). Không cache DRAFT/CALCULATED | Cache-aside + **pre-warm** khi `PayrollRunFinalized` | 7 ngày ± jitter (hot 3 ngày đầu) | Key chứa `runId` → bất biến; correction run tạo key mới + cập nhật pointer `latest` | Nội dung: không stale (bất biến). Pointer `latest`: **không**, invalidate ngay |
| **D. Tax rule** | **Có** — 2 tầng: local (Caffeine) + Redis | Read-through theo **version**; version bất biến | Bản version: 24h / không hết hạn. Pointer `active`: 5 phút | Publish rule mới = version mới; `TaxRulePublished` → pub/sub xoá pointer | Version: không stale. Pointer: có (≤ 5 phút) vì run **pin version** lúc snapshot |
| **E. Permission policy** | **Có** — local in-process, ngắn | Cache-aside local + pub/sub invalidation + `policyVersion` | 60s | `PolicyChanged` → broadcast xoá toàn tenant; so `policyVersion` | Grant mới: có (≤ 60s). **Revoke: tối đa 60s**; action nhạy cảm check trực tiếp |

## 3. Cache key design

```text
{service}:{schemaVersion}:{tenantId}:{entity}:{id}[:{qualifier}]

hr:v1:{tenantId}:emp:{employeeId}:profile
leave:v1:{tenantId}:balance:{employeeId}:{year}
pay:v1:{tenantId}:payslip:{runId}:{employeeId}         # bất biến
pay:v1:{tenantId}:payslip-latest:{employeeId}:{period} # pointer → runId, invalidate khi correction
tax:v1:{country}:rule:{ruleVersion}                    # bất biến, dùng chung mọi tenant
tax:v1:{country}:rule-active:{effectiveDate}           # pointer
authz:v1:{tenantId}:policy:{policyVersion}
```

- **`tenantId` luôn đứng đầu** → không lộ dữ liệu chéo tenant, xoá được theo tenant (offboarding).
- **`schemaVersion`** (`v1`): đổi format DTO thì lên `v2`, không cần flush toàn cluster.
- Data bất biến dùng **versioned key** → "invalidation" trở thành "đọc key mới", tránh race condition.
- Cache **không thay thế authorization**: check `employeeId == currentUser` / ABAC **trước** khi đọc cache.

## 4. Strategy — vì sao chọn / không chọn

| Strategy | Dùng ở đâu | Lý do |
| --- | --- | --- |
| **Cache-aside** | A, B (display), C, E | Đơn giản, cache chết vẫn đọc DB được; chỉ cache data thực sự được đọc |
| **Read-through** | D | Loader tập trung trong thư viện (Caffeine `LoadingCache`) → single-flight sẵn |
| **Write-through** | Không dùng | Ghi mọi update vào cache, kể cả data ít đọc; tăng latency ghi; payroll write burst 2h |
| **Write-behind** | **Cấm** với dữ liệu tài chính | Ghi cache trước, DB sau → cache chết là **mất dữ liệu lương/phép**; vi phạm RPO ≈ 0 |

**Thứ tự ghi (cache-aside)**: `UPDATE DB → commit → DEL cache` (không SET cache — tránh 2 writer ghi đè giá trị cũ).
Service khác invalidate qua **outbox event** để không mất lệnh xoá khi crash sau commit. TTL luôn có làm lưới an toàn.

```text
Read  GET /payslips/{period}
  authz check (ABAC) → GET payslip-latest → runId → GET payslip:{runId}:{emp}
    ├─ hit  → trả data
    └─ miss → single-flight lock → đọc replica → SET (TTL+jitter) → trả data
            (không tồn tại / chưa FINALIZED → SET marker NULL, TTL 30s)
Write PayrollRunFinalized → pre-warm payslip của run (batch, rate-limited) → SET payslip-latest
      CorrectionRunFinalized → SET payslip mới (runId mới) → DEL/SET payslip-latest
```

## 5. Failure scenarios

| Vấn đề | Tình huống trong Payroll | Xử lý |
| --- | --- | --- |
| **Cache stampede** | Email "payslip ready" 9h sáng → hàng nghìn miss cùng lúc; key hot hết hạn đồng thời | Pre-warm khi FINALIZED; **single-flight** (lock theo key / `LoadingCache`); TTL jitter; stale-while-revalidate cho profile |
| **Cache penetration** | Gọi `employeeId`/`period` không tồn tại, payslip chưa finalize, bot dò ID | Validate input; **negative cache** marker `NULL` TTL 30–60s; rate limit theo tenant/user |
| **Cache avalanche** | Redis restart / flush → toàn bộ traffic dồn DB | Redis cluster + replica; local L1 cho tax/policy; DB fallback có **bulkhead + rate limit**; circuit breaker |
| **Stale data** | Revoke quyền HR nhưng cache còn 60s; correction payslip | Revoke → pub/sub xoá ngay; action nhạy cảm (approve run, xem lương người khác) đọc policy từ DB; payslip dùng versioned key |
| **Distributed cache down** | Redis không phản hồi | Timeout 50ms, coi như miss; permission: lỗi cả cache lẫn DB → **fail closed (deny)** |
| **Hot key / big key** | Tax rule đọc bởi mọi worker khi tính lương | L1 local cache trong worker; giới hạn value < 100KB, PDF để object storage |

**DB có chịu được miss hàng loạt?** Payslip peak ~350 rps (bài 04). Cold cache 100% → 350 rps vào read replica,
query PK ~5ms → replica chịu được, nhưng p99 tăng. Vì vậy pre-warm + single-flight là bắt buộc, không phải tối ưu.

## 6. Metrics & Alert

| Metric | Ý nghĩa | Alert gợi ý |
| --- | --- | --- |
| `cache_hit_ratio{cache,tenant}` | Hiệu quả cache | Payslip < 90% trong 3 ngày sau lương |
| `cache_miss_total{reason=cold\|expired\|evicted\|negative}` | Vì sao miss | `evicted` tăng đột biến → thiếu memory |
| `cache_eviction_total`, `memory_used_ratio` | Áp lực bộ nhớ | memory > 80%, eviction > 0 với key bất biến |
| `cache_latency_ms{op=get\|set}` p99 | Redis có chậm không | p99 > 10ms |
| `db_fallback_total`, `db_fallback_rps` | Lượng request rơi xuống DB | fallback rps > 2× baseline |
| `cache_invalidation_lag_seconds` | Event → DEL mất bao lâu | > 30s (stale vượt cam kết) |
| `single_flight_wait_total`, `negative_cache_hit_total` | Stampede / penetration | Negative hit tăng mạnh → nghi bot dò ID |

## 7. Open Questions

1. Business chấp nhận revoke quyền trễ tối đa bao lâu? (quyết định TTL 60s của permission có đủ chặt không)
2. Payslip trong Redis có cần **mã hoá ở mức value** (PII lương) ngoài TLS + ACL không?
3. Tenant enterprise có yêu cầu cache **tách riêng** (dedicated Redis/namespace) vì compliance không?

## Checklist

- [x] Không cache chỉ vì đọc nhiều — có tiêu chí 3 điều kiện; leave balance (write path), run progress, audit **không** cache.
- [x] Mỗi data có invalidation strategy: event-driven DEL, versioned key, pub/sub, TTL làm lưới an toàn.
- [x] Phân biệt stale-tolerant (profile, display balance) và critical (payslip, tax version, revoke permission).
- [x] Có metric: hit rate, miss rate, eviction, latency, DB fallback, invalidation lag.
