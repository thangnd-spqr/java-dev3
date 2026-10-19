# 06 — Scalability, Availability & Observability: Payroll Service

> "Cần logging" không phải thiết kế. Mỗi NFR phải **đo được (SLI)**, có **mục tiêu (SLO)**, có **alert dẫn tới hành động**,
> và khi lỗi xảy ra team trả lời được: *lỗi ở đâu, ảnh hưởng tenant/run nào, vì sao*.

## 1. NFR → 3 câu hỏi

| NFR (Payroll Run API) | Scalability | Availability | Observability |
| --- | --- | --- | --- |
| Availability 99.9% | Gateway + API stateless, scale ngang theo RPS | ≥ 3 pod / 3 AZ, DB Multi-AZ, readiness probe | Burn-rate alert trên SLI availability |
| p95 create < 500ms | API chỉ INSERT + outbox, không tính lương sync | Timeout DB 300ms, circuit breaker | Histogram latency theo endpoint |
| Calc async < 30 phút | Worker autoscale theo `queue_depth`, chunk 1,000 | Worker crash → chunk retry (UPSERT idempotent) | `run_duration`, `eta_minutes`, DLQ |
| Duplicate run: 0 | — | Unique partial index sống sót qua failover | Reconciliation job đếm run active trùng |
| Audit loss: 0 | Audit store append-only, partition theo tháng | Outbox cùng tx, relay retry, không xoá khi chưa SENT | `outbox_pending_age`, đối soát số lượng |

**Cơ chế bảo vệ**: rate limit theo tenant ở gateway (429 + `Retry-After`) · timeout mọi outbound call ·
retry **chỉ** cho lỗi transient, exponential backoff + jitter, tối đa 3 lần, chỉ với thao tác idempotent ·
circuit breaker cho Tax/HR service · health check: `liveness` (process sống) tách `readiness` (DB + queue OK) — readiness fail
thì rút pod khỏi LB, **không** restart · failover DB tự động (RTO < 1–2 phút), app retry kết nối.

## 2. Năm SLI (good events / valid events)

| # | SLI | Công thức | Đo ở đâu |
| --- | --- | --- | --- |
| SLI-1 | **Run API availability** | request `/payroll-runs*` không 5xx / tổng request (loại 4xx do client) | API Gateway (góc nhìn user) |
| SLI-2 | **Create/submit latency** | request create/submit < 500ms / tổng request create/submit | Gateway histogram |
| SLI-3 | **Calculation timeliness** | run `QUEUED → CALCULATED` < 30 phút / tổng run kết thúc | Orchestrator (timestamp state) |
| SLI-4 | **Run uniqueness** | số `(tenant, payGroup, period)` có > 1 run active | Reconciliation job 5 phút/lần |
| SLI-5 | **Audit completeness** | audit event có trong audit store trong ≤ 5 phút / audit event trong outbox | Relay + đối soát hằng giờ |

## 3. Năm SLO & error budget

| # | SLO | Cửa sổ | Error budget |
| --- | --- | --- | --- |
| SLO-1 | Availability **≥ 99.9%** | rolling 30 ngày | 0.1% request ≈ **43 phút** downtime tương đương |
| SLO-2 | **95%** create/submit < 500ms, **99%** < 2s | rolling 28 ngày | 5% request được phép chậm hơn 500ms |
| SLO-3 | **99%** run tính xong < 30 phút; **100%** xong trước deadline trả lương | mỗi kỳ lương | 1% run (thường do tenant 100k) |
| SLO-4 | Duplicate run active = **0** | luôn luôn | **Không có budget** — vi phạm là SEV1 |
| SLO-5 | Audit loss = **0**; độ trễ p99 < 5 phút | luôn luôn / 30 ngày | Loss: không budget. Lag: 1% event |

**Error budget policy**: đốt > 50% budget SLO-1/2 trong tháng → chỉ deploy fix reliability; đốt hết → freeze release
(trừ hotfix) và bắt buộc postmortem. Không deploy thay đổi rủi ro trong **cửa sổ lương cuối tháng**.
SLO-4/5 là invariant tài chính/pháp lý → không đổi lấy tốc độ phát hành, bảo vệ bằng thiết kế + đối soát.

## 4. Correlation ID & trace xuyên service

```text
[HR browser] POST /payroll-runs  (X-Correlation-Id tuỳ chọn)
 └► API Gateway: validate/sinh correlationId (UUID), sinh W3C traceparent, gắn tenantId, userId
     └► Payroll Run API: MDC {correlationId, traceId, tenantId, userId} → sau INSERT thêm runId
         └─ 1 tx: INSERT payroll_runs + outbox(headers: traceparent, correlationId, tenantId, runId)
 └► Outbox Relay → Queue/Kafka headers giữ nguyên traceparent + correlationId
     └► Orchestrator: fan-out N chunk; mỗi chunk message mang {runId, chunkId, attempt}
         └► Worker: span "calculate-chunk" (span link về span submit) → DB spans, Tax service span
     └► Orchestrator: CALCULATED → FINALIZED → event PayrollRunFinalized (cùng correlationId, runId)
         └► Document / Notification / Accounting / Audit: audit record lưu correlationId + traceId
[Response] trả X-Correlation-Id trong header + body lỗi → support tra ngược toàn bộ flow
```

- **Không tin mù** ID từ client: chỉ nhận UUID hợp lệ, ≤ 64 ký tự; sai → sinh mới, log ID cũ làm `clientRequestId`.
- Flow async 30 phút, fan-out 100 chunk → dùng **span link** thay vì 1 trace khổng lồ; `runId` là khoá tra cứu chính.
- Sampling **tail-based**: giữ 100% trace lỗi/chậm (> 500ms), 10% trace bình thường; trace của run `FAILED` luôn giữ.
- Log JSON có cấu trúc, **không** log lương/số tài khoản (PII):

```json
{"ts":"2026-10-31T09:00:01Z","level":"ERROR","service":"calc-worker","correlationId":"7f3c…","traceId":"4bf9…",
 "tenantId":"t-123","runId":"r-889","chunkId":"c-42","attempt":2,"event":"chunk_failed","errorClass":"TaxRuleNotFound"}
```

## 5. Dashboard metrics

**Business metric** — gắn trực tiếp user/tiền/pháp lý (xem đầu tiên):

| Metric | Trả lời câu hỏi |
| --- | --- |
| `payroll_runs_completed_before_deadline_ratio` | Bao nhiêu % tenant được trả lương đúng hạn? |
| `payroll_runs_by_status{status}` (QUEUED/CALCULATING/FAILED…) | Kỳ lương đang kẹt ở bước nào? |
| `payslips_calculated_total`, `payslips_calculated_per_second` | Tốc độ có đủ xong 5M payslip trong 2h? |
| `run_eta_minutes{tenant_tier}` vs thời gian còn lại | Run nào có nguy cơ trễ deadline? |
| `duplicate_run_active_count`, `duplicate_run_rejected_total` | Có ai bị tính lương 2 lần không? Idempotency có chặn được không? |
| `calc_validation_error_total{reason}` | Lương sai do config/tax rule (HR phải sửa)? |
| `audit_events_missing_count` | Có lỗ hổng compliance không? |

**Technical metric** — giải thích *vì sao* business metric xấu:

| Nhóm | Metric |
| --- | --- |
| RED (API) | `http_requests_total{route,status}`, `http_request_duration_seconds` (p50/p95/p99), error rate |
| Queue/Worker | `queue_depth`, `queue_lag_seconds{tenant_tier}`, `chunk_duration_seconds`, `chunk_retry_total`, `dlq_size` |
| Resilience | `circuit_breaker_state{dependency}`, `retry_total{dependency}`, `rate_limited_total{tenant_tier}`, `timeout_total` |
| DB | connection pool usage, `db_query_duration_seconds`, `replication_lag_seconds`, lock wait, failover event |
| Outbox/Audit | `outbox_pending_count`, `outbox_pending_age_seconds{type}`, `relay_publish_error_total` |
| Saturation | CPU/memory pod, HPA replicas vs max, JVM GC pause |

> Label dùng `tenant_tier` (free/standard/enterprise), **không** dùng `tenantId` làm label Prometheus (50k tenant →
> cardinality nổ). Drill-down theo `tenantId`/`runId` thực hiện ở log và trace.

## 6. Năm alert — mỗi alert có hành động

| # | Alert | Điều kiện | Mức | Hành động (runbook) |
| --- | --- | --- | --- | --- |
| A1 | **SLO burn rate** (availability/latency) | Burn 14.4× trong 1h **và** 5m (đốt 2% budget/giờ); ticket nếu 6× trong 6h | Page | Xem dashboard theo route/dependency; vừa deploy → rollback; DB failover → kiểm tra pool reconnect; 1 tenant spam → siết rate limit |
| A2 | **Run có nguy cơ trễ deadline** | `run_eta_minutes > thời gian còn lại` hoặc run CALCULATING > 25 phút (chỉ trong cửa sổ lương) | Page | HPA đã max? → tăng max replica; queue lag dồn 1 tenant → chỉnh quota; báo CS cho tenant bị ảnh hưởng |
| A3 | **Phát hiện duplicate run** | `duplicate_run_active_count > 0` | SEV1 Page | Khoá approve/finalize các run liên quan; huỷ run trùng khi chưa FINALIZED; kiểm tra unique index/idempotency; báo tenant |
| A4 | **Audit pipeline nguy cơ mất dữ liệu** | `outbox_pending_age_seconds{type="audit"} > 300` hoặc đối soát lệch > 0 | Page | Kiểm tra relay/broker; **không** xoá outbox; replay từ outbox sau khi khôi phục; ghi nhận cho compliance |
| A5 | **Chunk thất bại vào DLQ** | `dlq_size > 0` cho run active hoặc chunk failure rate > 1%/10 phút | Page (cửa sổ lương) / Ticket | Phân loại `errorClass`: lỗi nghiệp vụ (tax rule, dữ liệu thiếu) → dừng run, báo HR; transient → redrive DLQ |

**Cố tình KHÔNG alert (chỉ để dashboard)**: CPU > 80% (autoscale xử lý), 1 pod restart, 4xx tăng do client,
retry thành công. Alert phải: (1) ảnh hưởng user/SLO, (2) cần người can thiệp, (3) có runbook link + dashboard link
+ ví dụ `runId`/`correlationId` trong nội dung. Alert nào bị bỏ qua 3 lần liên tiếp → xem lại ngưỡng hoặc xoá.

## Checklist

- [x] Không chỉ "cần logging": có SLI công thức, SLO + error budget, log có cấu trúc, trace sampling, cardinality.
- [x] Metric gắn business impact: trả lương đúng hạn, duplicate run, audit missing, validation error.
- [x] 5 alert đều có điều kiện cụ thể + runbook; liệt kê những gì cố tình không alert.
- [x] Correlation ID + traceparent đi qua Gateway → API → outbox → queue → worker → event → audit.

