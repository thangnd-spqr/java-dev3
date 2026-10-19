# 03 — Event-Driven Design: HR admin chạy payroll thành công

## 1. Command vs Event vs Message

| | Command | Event | Message |
| --- | --- | --- | --- |
| Ý nghĩa | **Yêu cầu** làm một việc (có thể bị từ chối) | **Sự thật đã xảy ra**, không thể từ chối | Phong bì vận chuyển chung |
| Đặt tên | Động từ mệnh lệnh: `RunPayroll`, `ReserveInventory` | Quá khứ: `PayrollCompleted`, `EmployeeSalaryChanged` | — |
| Target | **Một** handler rõ ràng | **0..N** consumer, producer không biết ai nghe | Tuỳ nội dung |
| Kênh | API sync / queue point-to-point | Topic pub/sub | Queue hoặc topic |

- **Domain event**: nội bộ bounded context, giàu chi tiết (`PayslipCalculated` cho từng nhân viên).
- **Integration event**: hợp đồng công khai giữa các service, tối giản, có version (`PayrollCompleted.v1`).
- **Producer** publish lên **message broker** (Kafka/RabbitMQ); **consumer** subscribe topic và xử lý độc lập.

## 2. Flow tổng thể

```text
HR Admin --POST /payroll-runs/{id}/run--> Payroll Service          (SYNC: cần kết quả ngay)
   Command: RunPayroll(tenantId, period, requestedBy, idempotencyKey)
   └── PayrollRun aggregate: validate → calculate → FINALIZED
   └── 1 transaction: UPDATE payroll_runs + INSERT outbox_events
Outbox Relay --> topic payroll.events --> PayrollCompleted.v1      (ASYNC)
   ├── Notification Service → gửi email payslip cho từng nhân viên
   ├── Reporting Service    → cập nhật dashboard chi phí lương
   ├── Audit Service        → lưu audit trail
   └── Accounting Service   → tạo bút toán chi phí lương
```

## 3. Command & Aggregate xử lý

| Thành phần | Chi tiết |
| --- | --- |
| Command | `RunPayroll { tenantId, period: "2026-10", requestedBy, idempotencyKey }` |
| Handler | `RunPayrollHandler` trong **Payroll Service** (data owner) |
| Aggregate | `PayrollRun` — invariant: 1 run active / tenant / tháng; run `FINALIZED` không sửa được |
| Kết quả | Trả `200 + runId` cho HR admin **đồng bộ**; lỗi validate → `409/422` ngay lập tức |

`idempotencyKey` (header `Idempotency-Key`) chống HR bấm 2 lần: lưu `(tenant_id, idempotency_key) UNIQUE`,
request trùng trả lại kết quả cũ, không chạy payroll lần hai.

## 4. Event publish

```json
{
  "eventId": "7f3c9a1e-...-uuid",
  "eventType": "PayrollCompleted",
  "schemaVersion": 1,
  "occurredAt": "2026-10-19T12:46:05Z",
  "tenantId": "t-001",
  "aggregateId": "run-2026-10-t-001",
  "correlationId": "req-abc123",
  "payload": { "period": "2026-10", "employeeCount": 250, "totalNet": 1250000000, "currency": "VND" }
}
```

- Partition key = `tenantId` → event cùng tenant giữ **thứ tự** trong một partition.
- Payload **không** chứa lương từng người (PII); consumer cần chi tiết gọi API Payroll có phân quyền.

## 5. Tránh "DB update thành công nhưng publish event thất bại" — Outbox Pattern

```sql
BEGIN;
  UPDATE payroll_runs SET status = 'FINALIZED' WHERE id = :runId AND status = 'CALCULATED';
  INSERT INTO outbox_events(event_id, aggregate_id, event_type, payload, status)
         VALUES (:eventId, :runId, 'PayrollCompleted', :json, 'PENDING');
COMMIT;   -- cả hai cùng thành công hoặc cùng rollback
```

- **Outbox relay** (poller hoặc CDC/Debezium) đọc `PENDING` → publish → đánh dấu `SENT`.
- Relay crash sau khi publish nhưng trước khi mark `SENT` → publish lại → **at-least-once** ⇒ consumer phải idempotent.
- Không dùng dual-write `repo.save(); kafka.send();` — mất event nếu crash giữa hai bước.

## 6. Consumer & Idempotency

| Consumer | Hành động | Idempotency |
| --- | --- | --- |
| Notification | Gửi email payslip | `processed_events(event_id)` + `sent_emails(run_id, employee_id) UNIQUE` |
| Reporting | Upsert dashboard theo `(tenant, period)` | Upsert tự nhiên idempotent (ghi đè cùng giá trị) |
| Audit | Ghi audit log | `audit_log.event_id UNIQUE` → insert trùng bị bỏ qua |
| Accounting | Tạo bút toán | `journal_entries(source_event_id) UNIQUE` |

```java
@Transactional
public void on(PayrollCompleted e) {
    if (processedEvents.existsById(e.eventId())) return;      // đã xử lý → bỏ qua
    reportingService.upsertPayrollSummary(e.tenantId(), e.period(), e.totalNet());
    processedEvents.save(new ProcessedEvent(e.eventId(), "reporting"));  // cùng transaction
}
```

## 7. Retry & DLQ Strategy

| Loại lỗi | Ví dụ | Xử lý |
| --- | --- | --- |
| Transient | SMTP timeout, DB deadlock, 503 | Retry **exponential backoff + jitter**: 1s → 5s → 30s → 2m → 10m (tối đa 5 lần) |
| Permanent | Payload sai schema, employee không tồn tại | **Không retry** → đẩy thẳng DLQ |
| Hết số lần retry | Dịch vụ email sập lâu | Chuyển `payroll.events.notification.dlq` |

- Retry dùng **retry topic** riêng (không block partition chính) — tránh 1 message lỗi chặn cả tenant.
- DLQ message kèm `error`, `stackTrace`, `attempts`, `originalTopic`; **alert** khi DLQ > 0.
- Sau khi fix: **replay** từ DLQ về topic gốc — an toàn vì consumer idempotent.
- Mỗi consumer có **consumer group + DLQ riêng**: Notification lỗi không ảnh hưởng Audit/Reporting.

## 8. Ordering & Schema Evolution

- Chỉ đảm bảo thứ tự **trong partition** (`tenantId`). Consumer kiểm tra `aggregateVersion`/`occurredAt`, bỏ event cũ hơn state hiện tại.
- Thay đổi **backward-compatible**: chỉ **thêm field optional**, không đổi tên/xoá/đổi kiểu field.
- Breaking change → event mới `PayrollCompleted.v2`, publish song song v1 đến khi mọi consumer migrate.
- Quản lý bằng **Schema Registry** (Avro/JSON Schema) với chế độ compatibility `BACKWARD`.

## 9. Không dùng event bus che giấu synchronous business flow

| Bước | Sync hay Async | Lý do |
| --- | --- | --- |
| Validate + tính lương + finalize | **Sync**, trong Payroll Service | HR cần biết ngay thành công/thất bại; là invariant cốt lõi |
| Kiểm tra quyền chạy payroll | **Sync** | Không thể "chạy trước, từ chối sau" |
| Email, dashboard, audit, accounting | **Async** qua event | Side effect, chậm vài giây chấp nhận được, nhiều consumer |

> Không publish `RunPayrollRequested` rồi trả `200 OK` khi chưa biết lương có tính đúng không —
> đó là dùng event để giấu lỗi. Event chỉ phát **sau khi** sự thật nghiệp vụ đã được commit.

## Checklist

- [x] Phân biệt được event và command — `RunPayroll` (yêu cầu, 1 handler) vs `PayrollCompleted` (sự thật, N consumer).
- [x] Biết event consumer phải idempotent — at-least-once delivery, dedupe theo `eventId`.
- [x] Có error/retry/DLQ plan — phân loại lỗi, backoff, DLQ riêng từng consumer, replay.
- [x] Không dùng event bus để che giấu synchronous business flow quan trọng.

