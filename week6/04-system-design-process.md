# 04 — System Design Process: Payroll SaaS

> Thứ tự: **requirement → NFR → số liệu → component → flow → failure → technology**.
> Technology chỉ được chọn khi đã có lý do từ các bước trước.

## 1. Ai dùng & làm gì (Functional Requirements)

| Actor | Hành động chính |
| --- | --- |
| HR Admin (tenant) | Tạo payroll run theo `(tenant, payGroup, period)`, submit tính lương, xem tiến độ, review, approve/finalize |
| Employee | Xem payslip (web/mobile), tải PDF, xem lịch sử các tháng trước |
| Auditor / Compliance | Tra cứu ai đã tạo/sửa/approve run, ai xem payslip — trong 7 năm |
| Hệ thống | Tính lương async theo chunk, sinh payslip, phát event cho email/kế toán/báo cáo |

- **FR1** Tạo run: mỗi `(tenantId, payGroupId, period)` chỉ có **1 run active** — không duplicate.
- **FR2** Submit run → trả `202 Accepted + runId` ngay; tính lương chạy nền, có API xem `progress %`.
- **FR3** Run đi qua state: `DRAFT → QUEUED → CALCULATING → CALCULATED → APPROVED → FINALIZED` (hoặc `FAILED`).
- **FR4** Employee xem payslip của chính mình sau khi run `FINALIZED`.
- **FR5** Mọi thay đổi nghiệp vụ và truy cập dữ liệu lương đều được ghi audit, không sửa/xoá được.

## 2. Non-Functional Requirements

| NFR | Mục tiêu | Ghi chú |
| --- | --- | --- |
| Payslip read latency | **p99 < 2s** (mục tiêu nội bộ p95 < 300ms) | Đọc dữ liệu đã tính sẵn, không tính lại khi xem |
| Month-end throughput | 10,000 runs hoàn tất trong **2 giờ** | Async, scale ngang worker |
| Correctness | **Không duplicate run**, không mất/nhân đôi payslip | Idempotency ở mọi tầng |
| Durability | RPO ≈ 0 cho dữ liệu đã finalize; RTO < 1h | Multi-AZ, PITR backup |
| Audit retention | **≥ 7 năm**, append-only, WORM | Tách khỏi DB nghiệp vụ |
| Tenant isolation | Tenant lớn không làm chậm tenant nhỏ; không lộ dữ liệu chéo | Fair scheduling + `tenant_id` ở mọi query |
| Security | Lương là PII nhạy cảm: encrypt at rest/in transit, RBAC/ABAC | — |

## 3. Assumptions

- 5M employees / 50k tenants → trung bình **100 nhân viên/tenant**, phân phối lệch (đa số < 100, vài tenant 100k).
- Worst case: cửa sổ 2h cuối tháng tính lương cho **toàn bộ 5M employees**.
- Tính 1 payslip (đọc salary, attendance, tax rule, ghi kết quả) ≈ **20ms CPU+IO** trên 1 thread.
- 1 payslip lưu ≈ **5KB** (header + line items dạng JSON); PDF ≈ **100KB**; ~10 audit records/employee/tháng × 1KB.
- ~3 lượt xem payslip/employee/tháng, 60% dồn vào 3 ngày đầu sau ngày trả lương (giờ hành chính).

## 4. Capacity Estimation (rough)

| Hạng mục | Phép tính | Kết quả |
| --- | --- | --- |
| Run submit | 10,000 / 7,200s; burst 10× (mọi người bấm lúc 9h) | ~1.4 run/s, peak **~15 run/s** |
| Payslip calc | 5M / 7,200s; peak factor 3× | ~700/s avg, **~2,100/s peak** |
| Worker thread | 2,100 ÷ (1000ms / 20ms = 50/s/thread) | ~42 thread → ×2 headroom ≈ **84 thread** (~11 pod × 8) |
| Run 100k employees | chunk 1,000 employee → 100 chunk song song | 1 chunk ≈ 20s ⇒ run lớn xong trong **vài phút** nếu đủ worker |
| DB write peak | 2,100 payslip/s, batch insert 500 rows | ~5 batch/s — Postgres chịu được; bottleneck là **lock & index** |
| Payslip read | 3M lượt / (3 ngày × 8h); peak 10× | ~35 rps avg, **~350 rps peak** |
| Read/write ratio | ~15M read : 5M write / tháng | **3:1**, nhưng write dồn burst 2h, read trải nhiều ngày |
| Payslip storage | 5M × 5KB × 12 × 7 năm (+50% index) | 25GB/tháng → **~3TB / 7 năm** |
| PDF storage | 5M × 100KB × 12 × 7 năm | 500GB/tháng → **~42TB** → object storage + cold tier |
| Audit storage | 5M × 10 × 1KB × 12 × 7 năm | 50GB/tháng → **~4.2TB**, WORM |

## 5. Thành phần chính

| Component | Trách nhiệm | Vì sao cần (từ requirement) |
| --- | --- | --- |
| API Gateway | AuthN, tenant resolution, rate limit theo tenant | Isolation, chống burst 9h sáng |
| Payroll Run API | Tạo/submit/approve run, idempotency, state machine | FR1–FR3, chống duplicate |
| Run Orchestrator | Chia run thành chunk, theo dõi chunk done, chuyển state | Run 100k employees, async |
| Job Queue | Đệm chunk, fair scheduling per tenant, retry/DLQ | 10k runs dồn 2h → cần buffer |
| Calculation Workers | Stateless, tính lương từng chunk, batch write | 2,100 payslip/s, scale ngang |
| Payroll DB (primary) | `payroll_runs`, `payroll_chunks`, `payslips`, `outbox`; partition theo `period` | Source of truth, transaction |
| Payslip Read Service | Đọc từ **read replica** + cache theo `(tenant, employee, period)` | p99 < 2s, tách read khỏi write burst |
| Document Service | Sinh PDF async sau FINALIZED → object storage, URL có hạn | PDF 42TB không để trong DB |
| Audit Service | Nhận audit qua outbox → append-only store, WORM 7 năm | FR5, retention |
| Outbox Relay + Event Bus | Phát `PayrollRunFinalized` cho email/kế toán/báo cáo | Không mất event khi DB commit |

## 6. Data Flow

```text
[1] HR → POST /payroll-runs {payGroup, period}  Header: Idempotency-Key
      → INSERT payroll_runs (UNIQUE tenant_id, pay_group_id, period WHERE status <> 'CANCELLED')
      → trùng key/unique → trả lại run cũ (200) hoặc 409
[2] HR → POST /payroll-runs/{id}/submit → status QUEUED → 202 Accepted
[3] Orchestrator: snapshot danh sách employee → tạo N chunk (1,000/chunk) → INSERT chunks + outbox (1 tx)
      → relay đẩy chunk vào Job Queue (key = tenantId, quota per tenant)
[4] Worker: lấy chunk → tính → batch UPSERT payslips (UNIQUE run_id, employee_id) → mark chunk DONE
[5] Orchestrator: done_chunks == total → CALCULATED → HR review → APPROVED → FINALIZED (+ outbox event)
[6] Event → Document Service (PDF), Notification (email), Accounting, Audit
[7] Employee → GET /payslips/{period} → cache → read replica → (PDF qua pre-signed URL)
```

## 7. Bottleneck & Failure Scenarios

| Scenario | Ảnh hưởng | Thiết kế xử lý | Metric / Alert |
| --- | --- | --- | --- |
| HR bấm 2 lần / client retry | Duplicate run | `Idempotency-Key` + unique partial index | `duplicate_run_rejected_total` |
| Worker crash giữa chunk | Chunk dở dang | Ack sau khi commit; UPSERT idempotent → retry an toàn | `chunk_retry_total`, `chunk_age_max` |
| Tenant 100k chiếm hết worker | Tenant nhỏ trễ deadline | Quota chunk in-flight / tenant, weighted fair queue | `queue_lag_by_tenant` |
| Queue backlog cuối tháng | Không xong trong 2h | Autoscale worker theo queue depth; capacity test trước kỳ lương | `queue_depth`, `eta_minutes` |
| Tax rule/config sai | Lương sai hàng loạt | Bước `CALCULATED → APPROVED` có review; FINALIZED immutable, sửa bằng correction run | `calc_validation_error_total` |
| DB primary failover | Write lỗi tạm thời | Multi-AZ, retry transient có backoff; worker resume theo chunk status | `db_failover`, `write_error_rate` |
| Replica lag lớn | Employee chưa thấy payslip | Chỉ publish "payslip ready" khi replica bắt kịp; fallback primary có giới hạn | `replication_lag_seconds` |
| Cache stampede sau email | Read latency > 2s | Pre-warm cache khi FINALIZED, request coalescing | `payslip_read_p99`, `cache_hit_ratio` |
| Audit/outbox relay chết | Mất audit / event | Audit ghi outbox trong cùng tx; relay retry; không xoá outbox chưa SENT | `outbox_pending_age` |

**SLO dashboard từ ngày đầu**: `payslip_read_latency p99`, `% runs xong trước deadline`, `payslips_calculated/s`,
`chunk_failed / DLQ size`, `queue_lag`, `replication_lag`, `outbox_lag`. Mọi log/trace mang `tenantId, runId, chunkId, correlationId`.

## 8. Architecture Decisions (ADR tóm tắt)

- **ADR-1** Tính lương async theo chunk (không 1 request dài) — vì run 100k employees và burst 10k runs.
- **ADR-2** Chống duplicate bằng **DB unique constraint** (nguồn sự thật), idempotency key chỉ là lớp ngoài.
- **ADR-3** Tách read path (replica + cache) khỏi write path — để burst tính lương không ảnh hưởng SLA 2s.
- **ADR-4** Audit là store riêng append-only/WORM — retention 7 năm khác vòng đời dữ liệu nghiệp vụ.

## 9. Open Questions

1. "10,000 runs trong 2 giờ" là cửa sổ **submit** hay deadline **hoàn tất**? Có SLA cho từng run không?
2. Một tenant có nhiều pay group / nhiều run trong tháng (off-cycle, bonus) không? Ảnh hưởng unique key.
3. Có cho **re-run** sau khi FINALIZED không, hay chỉ correction/adjustment run?
4. Hỗ trợ một hay nhiều quốc gia (tax rule, currency, **data residency**)?
5. Audit có bao gồm cả **read access** (ai xem payslip) không? Đó là yếu tố làm audit volume tăng mạnh.
6. PDF bắt buộc sinh sẵn hay on-demand? (đánh đổi 42TB storage vs latency lần xem đầu)
7. RPO/RTO chính thức? Tenant enterprise có cần dedicated DB/schema không?

## Checklist

- [x] Bắt đầu từ actor, hành động và requirement — technology chỉ xuất hiện như hệ quả.
- [x] Có capacity estimate (throughput, worker, read rps, storage 7 năm) dù là rough estimate.
- [x] Nêu rõ assumption ở mục 3 và câu hỏi mở ở mục 9.
- [x] Có failure scenario, metric và SLO dashboard ngay từ thiết kế đầu tiên.

