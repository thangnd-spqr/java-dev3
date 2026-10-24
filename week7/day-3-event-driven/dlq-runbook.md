# DLQ Runbook – `order.events.v1.*.dlq`

> DLQ không phải thùng rác. Mỗi message trong DLQ là **một nghiệp vụ chưa hoàn tất** (khách chưa nhận email, thiếu audit, dashboard sai) và phải có chủ, có SLA, có cách replay.

## 1. Retry → DLQ flow

```text
order.events.v1 ──▶ consumer (group X)
                     │ exception
                     ▼
              retry in-memory: attempt 1 → (1s) 2 → (2s) 3 → (4s) 4
                     │ vẫn lỗi  (hoặc non-retryable → vào thẳng)
                     ▼
         order.events.v1.<consumer>.dlq   (record gốc + header lỗi) → commit offset
                     │
              alert + người trực điều tra → fix → replay có kiểm soát → order.events.v1.<consumer>.replay
```

| Loại lỗi | Ví dụ | Retry? | Hành động |
|---|---|---|---|
| Transient | DB timeout, SMTP 503, deadlock | ✅ 4 attempts, backoff 1s/2s/4s | Thường tự khỏi; vào DLQ khi sự cố kéo dài → replay sau khi hạ tầng ổn |
| Poison / data | JSON hỏng, thiếu `customerEmail`, `schemaVersion` không hỗ trợ | ❌ vào DLQ ngay (`retryable-exceptions: false`) | Sửa dữ liệu nguồn hoặc deploy consumer hỗ trợ schema mới |
| Bug code | NPE, mapping sai | ❌/✅ | Fix code, deploy, replay |
| Business | Order không tồn tại trong hệ thống đích | ❌ | Điều tra thứ tự event / dữ liệu |

## 2. Thông tin bắt buộc trên mỗi message DLQ

| Thông tin | Nguồn |
|---|---|
| Lý do vào DLQ | `x-exception-fqcn`, `x-exception-message`, `x-exception-stacktrace` (binder Kafka tự thêm) |
| Vị trí gốc | `x-original-topic`, `x-original-partition`, `x-original-offset`, `x-original-timestamp` |
| Định danh nghiệp vụ | header `eventId`, `eventType`, key = `aggregateId` (orderId) |
| Số lần đã retry | = `max-attempts` của binding (4) với lỗi retryable, 1 với non-retryable; log `attempt=N` theo `eventId`; khi replay thêm header `x-replay-count` |
| Consumer/owner | Tên DLQ topic → bảng ownership dưới |

Ngoài Kafka, một **DLQ ingest job** đọc các topic DLQ và ghi vào bảng để tra cứu/thống kê/đánh dấu trạng thái:

```sql
CREATE TABLE dlq_messages (
    id               BIGSERIAL PRIMARY KEY,
    dlq_topic        VARCHAR(128) NOT NULL,
    consumer_group   VARCHAR(64)  NOT NULL,
    event_id         UUID,
    event_type       VARCHAR(128),
    aggregate_id     VARCHAR(64),
    original_topic   VARCHAR(128) NOT NULL,
    original_partition INT        NOT NULL,
    original_offset  BIGINT       NOT NULL,
    exception_class  VARCHAR(255),
    exception_message TEXT,
    attempts         INT          NOT NULL,
    payload          TEXT         NOT NULL,     -- raw, có thể không parse được
    status           VARCHAR(16)  NOT NULL DEFAULT 'NEW',  -- NEW | INVESTIGATING | FIXED | REPLAYED | DISCARDED
    owner_team       VARCHAR(64)  NOT NULL,
    ticket           VARCHAR(64),
    resolution_note  TEXT,
    replay_count     INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at      TIMESTAMPTZ,
    resolved_by      VARCHAR(64),
    UNIQUE (dlq_topic, original_partition, original_offset)
);
```

## 3. Ownership & SLA

| DLQ topic | Owner | Kênh alert | Mức độ | SLA xử lý | Tác động khi tồn |
|---|---|---|---|---|---|
| `order.events.v1.notification.dlq` | Team Customer Communication | `#cc-oncall` | P2 | 4 giờ làm việc | Khách không nhận email xác nhận |
| `order.events.v1.audit.dlq` | Team Platform/Compliance | `#platform-oncall` + PagerDuty | **P1** | 1 giờ | Thiếu audit trail (rủi ro compliance) |
| `order.events.v1.analytics.dlq` | Team Data | `#data-alerts` | P3 | 1 ngày làm việc | Dashboard lệch số liệu |

## 4. Alert

| Điều kiện | Hành động |
|---|---|
| DLQ có message mới (`kafka_topic_partition_current_offset{topic=~".*\\.dlq"}` tăng) | Gửi alert cho owner kèm eventId + exception |
| > 50 message / 10 phút cùng `exception_class` | Sự cố hệ thống → **không** xử lý lẻ từng message; tìm root cause |
| `dlq_messages` status `NEW` quá SLA | Escalate lead team |
| Retry rate (`deliveryAttempt > 1`) tăng đột biến | Cảnh báo sớm trước khi vào DLQ |

## 5. Quy trình xử lý (triage)

1. **Acknowledge** alert, set `status = INVESTIGATING`, tạo ticket, ghi `ticket` vào `dlq_messages`.
2. **Phân loại** theo `exception_class` + `exception_message`:
   ```sql
   SELECT consumer_group, exception_class, count(*), min(created_at), max(created_at)
   FROM dlq_messages WHERE status IN ('NEW','INVESTIGATING')
   GROUP BY 1,2 ORDER BY 3 DESC;
   ```
3. **Xem message**:
   ```bash
   kafka-console-consumer --bootstrap-server $BROKER --topic order.events.v1.audit.dlq \
     --from-beginning --property print.key=true --property print.headers=true --max-messages 20
   ```
4. **Xác định cách sửa**:

   | Nguyên nhân | Cách sửa | Có replay? |
   |---|---|---|
   | Hạ tầng (DB/SMTP down) | Chờ/khôi phục hạ tầng | ✅ replay nguyên bản |
   | Bug consumer | Fix code + test với payload từ DLQ + deploy | ✅ sau khi deploy |
   | Schema mới consumer chưa hỗ trợ | Deploy consumer hỗ trợ `schemaVersion` mới | ✅ |
   | Dữ liệu sai trong event (vd. email null) | Sửa ở **nguồn** (Order Service), phát event mới/bù. Chỉ khi bất khả kháng mới sửa payload khi replay – **giữ nguyên `eventId`**, ghi `resolution_note` + người duyệt | ✅ có review |
   | Event không còn ý nghĩa (order đã hủy, email quá cũ không nên gửi) | Không replay | ❌ `DISCARDED` + lý do |

5. **Không bao giờ** xóa topic DLQ / reset offset DLQ để "dọn alert".

## 6. Kế hoạch replay DLQ có kiểm soát

### Nguyên tắc

- **Root cause đã được fix và verify** trước khi replay (nếu không message chỉ quay lại DLQ).
- Replay **cùng `eventId`** → consumer idempotent đảm bảo không gửi email/ghi audit trùng nếu message thực ra đã được xử lý một phần.
- Replay **chỉ cho consumer bị lỗi**: gửi vào topic riêng `order.events.v1.<consumer>.replay`, **không** đẩy lại vào `order.events.v1` (nếu không, Notification/Analytics vốn đã xử lý OK sẽ nhận lại – dù idempotent vẫn là tải và rủi ro không cần thiết).
- **Có giới hạn tốc độ, có lọc, có dry-run, có audit** ai replay gì.

### Binding nhận replay

Consumer bị lỗi có thêm input binding thứ hai trỏ vào topic replay, **dùng chung handler**:

```yaml
spring:
  cloud:
    function:
      definition: auditConsumer;auditReplayConsumer
    stream:
      bindings:
        auditReplayConsumer-in-0:
          destination: order.events.v1.audit.replay
          group: audit-service-replay
          consumer:
            max-attempts: 2
      kafka:
        bindings:
          auditReplayConsumer-in-0:
            consumer:
              enable-dlq: true
              dlq-name: order.events.v1.audit.dlq     # lỗi lại -> về DLQ, replay_count tăng
```

```java
@Bean
public Consumer<Message<EventEnvelope<OrderConfirmedPayload>>> auditReplayConsumer(AuditHandler h) {
    return h::handle;   // cùng logic + cùng dedupe processed_events
}
```

### Replay tool (admin endpoint / CLI, chạy trong Ops service)

```java
@RestController
@RequestMapping("/admin/dlq")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DLQ_OPERATOR')")
class DlqReplayController {
    private final DlqReplayService service;

    @PostMapping("/replay")
    public ReplayReport replay(@RequestBody ReplayRequest req, Principal who) {
        return service.replay(req, who.getName());
    }
}

record ReplayRequest(
        String dlqTopic,                 // order.events.v1.audit.dlq
        List<UUID> eventIds,             // replay từng event, hoặc
        String exceptionClass,           // lọc theo loại lỗi
        Instant from, Instant to,        // khoảng thời gian vào DLQ
        int ratePerSecond,               // mặc định 10
        int maxMessages,                 // chặn trên, mặc định 100
        boolean dryRun,                  // true = chỉ liệt kê
        String ticket) {}
```

```java
public ReplayReport replay(ReplayRequest req, String operator) {
    List<DlqMessage> targets = repo.find(req);                 // status IN (NEW, FIXED), replay_count < 3
    if (req.dryRun()) return ReplayReport.dryRun(targets);

    RateLimiter limiter = RateLimiter.create(req.ratePerSecond());
    for (DlqMessage m : targets.subList(0, Math.min(req.maxMessages(), targets.size()))) {
        limiter.acquire();
        ProducerRecord<String, String> r = new ProducerRecord<>(replayTopicOf(m), m.aggregateId(), m.payload());
        r.headers().add("eventId", m.eventId().toString().getBytes(UTF_8));     // GIỮ eventId
        r.headers().add("x-replay-count", String.valueOf(m.replayCount() + 1).getBytes(UTF_8));
        r.headers().add("x-replayed-by", operator.getBytes(UTF_8));
        r.headers().add("x-replay-ticket", req.ticket().getBytes(UTF_8));
        kafka.send(r).get();
        repo.markReplayed(m.id(), operator, req.ticket());
    }
    return ReplayReport.of(targets);
}
```

### Các bước replay

| # | Bước | Lệnh / kiểm tra | Điều kiện đi tiếp |
|---|---|---|---|
| 1 | Xác nhận fix | Deploy xong, health OK, test 1 payload lấy từ DLQ ở staging | Pass |
| 2 | Dry-run | `POST /admin/dlq/replay {"dlqTopic":"order.events.v1.audit.dlq","exceptionClass":"java.lang.IllegalStateException","dryRun":true,"ticket":"OPS-123"}` | Danh sách & số lượng đúng kỳ vọng |
| 3 | Canary | Replay **1 event** (`eventIds:[…]`) | Log `AUDIT_RECORDED`, có row `audit_records`, không quay lại DLQ |
| 4 | Batch nhỏ | `maxMessages: 50, ratePerSecond: 5` | Error rate replay consumer = 0, lag về 0 |
| 5 | Toàn bộ | `maxMessages: 1000, ratePerSecond: 20` (theo dõi DB CPU/consumer lag) | DLQ không tăng |
| 6 | Verify | SQL đối soát bên dưới | Khớp |
| 7 | Đóng | `status = REPLAYED`, `resolved_by`, `resolution_note`, đóng ticket, postmortem nếu P1 | — |

**Dừng ngay** (kill switch: `ratePerSecond: 0` / tắt binding `auditReplayConsumer` qua `/actuator/bindings/auditReplayConsumer-in-0` `{"state":"STOPPED"}`) nếu message replay quay lại DLQ hoặc hệ thống đích quá tải.

### Đối soát sau replay

```sql
-- Mọi order CONFIRMED trong khoảng sự cố đều đã có audit
SELECT o.id FROM orders o
LEFT JOIN audit_records a ON a.order_id = o.id AND a.action = 'ORDER_CONFIRMED'
WHERE o.status = 'CONFIRMED' AND o.confirmed_at BETWEEN :incidentStart AND :incidentEnd
  AND a.id IS NULL;                         -- kỳ vọng 0 dòng

-- Không có audit trùng
SELECT event_id, count(*) FROM audit_records GROUP BY event_id HAVING count(*) > 1;   -- 0 dòng

-- Không có email trùng
SELECT order_id, count(*) FROM notification_log WHERE status='SENT' GROUP BY order_id HAVING count(*) > 1;
```

### Replay message không còn trong Kafka (quá retention)

Payload đã được lưu trong `dlq_messages.payload` → replay tool đọc từ bảng thay vì topic. Trường hợp mất hẳn: **regenerate từ nguồn** – Order Service có endpoint admin chèn lại outbox event `OrderConfirmed` cho danh sách order (eventId mới, nhưng consumer dedupe thêm theo khóa tự nhiên `order_id + template` / `fact_order_confirmed.order_id`).

## 7. Những điều KHÔNG làm

- ❌ Đặt `max-attempts` rất lớn để "khỏi vào DLQ" → partition bị kẹt, lag tăng, các order khác chờ.
- ❌ Replay khi chưa fix root cause.
- ❌ Replay vào topic chính khiến mọi consumer nhận lại.
- ❌ Sửa payload mà đổi `eventId`.
- ❌ Replay toàn bộ không rate limit sau sự cố lớn (thundering herd vào DB/SMTP).
- ❌ Message DLQ không có owner, không có alert.

