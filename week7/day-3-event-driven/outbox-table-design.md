# Transactional Outbox – Table Design & Publisher

## 1. Dual-write problem

Code cũ ghi vào **hai hệ thống** (PostgreSQL + Kafka) mà không có transaction chung:

```java
@Transactional
public void confirm(OrderId id) {
    order.confirm();
    orderRepository.save(order);          // (1) DB
    streamBridge.send("order-events", e); // (2) Kafka
}
```

| Kịch bản | Kết quả |
|---|---|
| (1) commit OK, app crash / Kafka down trước (2) | Order `CONFIRMED` nhưng **không có event** → không email, không audit, analytics thiếu. Mất vĩnh viễn, không ai biết |
| (2) gửi OK (nằm trong `@Transactional`), sau đó commit DB fail (constraint, deadlock, mất kết nối) | **Event ma**: email "đơn đã xác nhận" gửi cho order mà DB vẫn `PENDING` |
| (2) timeout nhưng thực ra broker đã nhận → code retry | Event duplicate (cái này consumer idempotent xử lý được) |

Đổi thứ tự hay try/catch không giải quyết được: không thể commit nguyên tử hai hệ thống khác nhau (2PC/XA với Kafka không thực tế).

## 2. Ý tưởng Outbox

Chỉ ghi vào **một** hệ thống – database – trong **một** local transaction. Việc đưa event ra Kafka là một bước riêng, **retry được vô hạn** vì dữ liệu nguồn đã bền vững.

```sql
BEGIN;
  UPDATE orders SET status = 'CONFIRMED', version = version + 1 WHERE id = 'ord-1001' AND version = 3;
  INSERT INTO outbox_events (...) VALUES (... 'OrderConfirmed' ...);
COMMIT;      -- cả hai cùng thành công hoặc cùng rollback
```

```text
Outbox Publisher (poller) hoặc CDC (Debezium đọc WAL)
        ↓  publish (key = aggregateId)
      Kafka order.events.v1
        ↓  ack từ broker (acks=all)
UPDATE outbox_events SET status='PUBLISHED'
```

Guarantee: **at-least-once** – event chắc chắn tới Kafka *ít nhất một lần*. Crash giữa "Kafka ack" và "mark PUBLISHED" → lần sau publish lại **cùng `eventId`** → consumer dedupe (xem [consumer-idempotency.md](consumer-idempotency.md)).

## 3. Schema `outbox_events` (PostgreSQL)

```sql
CREATE TABLE outbox_events (
    id               UUID         PRIMARY KEY,               -- = eventId trong envelope
    aggregate_type   VARCHAR(64)  NOT NULL,                  -- 'Order'
    aggregate_id     VARCHAR(64)  NOT NULL,                  -- 'ord-1001' -> Kafka key
    aggregate_version BIGINT      NOT NULL,
    event_type       VARCHAR(128) NOT NULL,                  -- 'OrderConfirmed'
    schema_version   INT          NOT NULL DEFAULT 1,
    topic            VARCHAR(128) NOT NULL,                  -- 'order.events.v1'
    payload          JSONB        NOT NULL,                  -- full envelope
    headers          JSONB        NOT NULL DEFAULT '{}',     -- correlationId, traceparent...
    status           VARCHAR(16)  NOT NULL DEFAULT 'NEW',    -- NEW | PUBLISHED | FAILED
    attempts         INT          NOT NULL DEFAULT 0,
    last_error       TEXT,
    occurred_at      TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ,
    CONSTRAINT uq_outbox_aggregate_version UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

-- Poller chỉ quét bản ghi chưa publish -> partial index nhỏ, nhanh
CREATE INDEX idx_outbox_pending
    ON outbox_events (next_attempt_at, created_at)
    WHERE status = 'NEW';
```

| Cột | Lý do |
|---|---|
| `id` = `eventId` | Sinh **trong transaction nghiệp vụ**, không sinh lúc publish → publish lại vẫn cùng ID, consumer dedupe được |
| `aggregate_id` | Làm Kafka key → giữ thứ tự theo order |
| `aggregate_version` + unique | Không thể ghi 2 event `OrderConfirmed` cho cùng version (chống bug double-confirm) |
| `topic` | Một outbox cho nhiều loại event/topic |
| `payload JSONB` | Lưu sẵn envelope hoàn chỉnh → publisher chỉ "bê" đi, không cần biết domain |
| `status`, `attempts`, `last_error`, `next_attempt_at` | Retry có backoff, quan sát được, chuyển `FAILED` khi vượt ngưỡng để alert |
| `published_at` | Đo **outbox lag** = `published_at - created_at` |

**Housekeeping**: job xóa bản ghi `PUBLISHED` cũ hơn 7 ngày (hoặc partition bảng theo ngày rồi `DROP PARTITION`) để bảng không phình.

## 4. Ghi outbox trong cùng transaction

```java
@Service
@RequiredArgsConstructor
public class ConfirmOrderService {
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    @Transactional
    public void confirm(String orderId, String causationId) {
        Order order = orders.findById(orderId).orElseThrow();
        order.confirm();                                  // domain rule: chỉ PAID -> CONFIRMED
        orders.save(order);

        var envelope = new EventEnvelope<>(
                UUID.randomUUID(), "OrderConfirmed", "Order", order.getId(), order.getVersion(),
                Instant.now(), 1, "order-service", MDC.get("correlationId"), causationId,
                OrderConfirmedPayload.from(order));

        outbox.save(OutboxEvent.of(envelope, "order.events.v1", mapper));
        // KHÔNG gọi Kafka ở đây
    }
}
```

## 5. Outbox Publisher (polling, Spring Cloud Stream `StreamBridge`)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {
    private final OutboxJdbcRepository repo;
    private final KafkaTemplate<String, String> kafka;   // hoặc StreamBridge

    @Scheduled(fixedDelayString = "${outbox.poll-interval:500}")
    public void publishBatch() {
        List<OutboxEvent> batch = repo.lockNextBatch(100);   // SELECT ... FOR UPDATE SKIP LOCKED
        for (OutboxEvent e : batch) {
            try {
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(e.topic(), e.aggregateId(), e.payload());
                record.headers().add("eventId", e.id().toString().getBytes(UTF_8));
                record.headers().add("eventType", e.eventType().getBytes(UTF_8));
                kafka.send(record).get(5, TimeUnit.SECONDS);   // chờ ack (acks=all)
                repo.markPublished(e.id());
            } catch (Exception ex) {
                repo.markRetry(e.id(), ex.getMessage(), backoff(e.attempts()));  // FAILED nếu attempts >= 10
                log.warn("OUTBOX_PUBLISH_FAILED eventId={} attempts={}", e.id(), e.attempts() + 1, ex);
                break; // dừng batch để không publish event sau của cùng aggregate trước event lỗi
            }
        }
    }
}
```

```sql
-- lockNextBatch: nhiều instance chạy song song không lấy trùng bản ghi
SELECT * FROM outbox_events
WHERE status = 'NEW' AND next_attempt_at <= now()
ORDER BY created_at
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

Producer config:

```yaml
spring:
  kafka:
    producer:
      acks: all
      retries: 2147483647
      properties:
        enable.idempotence: true               # chống duplicate do retry nội bộ producer
        max.in.flight.requests.per.connection: 5
        delivery.timeout.ms: 30000
```

> Với Spring Cloud Stream có thể thay `KafkaTemplate` bằng `streamBridge.send("orderEvents-out-0", MessageBuilder.withPayload(json).setHeader(KafkaHeaders.KEY, aggregateId).build())`. Model `Supplier<Message<?>>` (được binder poll mỗi giây) cũng dùng được, nhưng `@Scheduled` + `StreamBridge` dễ kiểm soát batch/ack hơn.

## 6. Polling vs CDC

| | Polling publisher | CDC (Debezium + Outbox Event Router) |
|---|---|---|
| Cách làm | Job `SELECT ... SKIP LOCKED` định kỳ | Đọc WAL/binlog, route theo cột `aggregate_type` |
| Độ trễ | = poll interval (≈ 0.5–1s) | Gần real-time (ms) |
| Tải DB | Query định kỳ | Gần như không (đọc log) |
| Vận hành | Chỉ cần app | Cần Kafka Connect, replication slot, theo dõi slot lag |
| Phù hợp | Team nhỏ, lưu lượng vừa | Lưu lượng lớn, nhiều service dùng chung pattern |

## 7. Monitoring outbox

| Metric | Alert |
|---|---|
| `outbox_pending_count` (status=NEW) | > 1000 trong 5 phút |
| `outbox_oldest_pending_age_seconds` | > 60s → publisher chết hoặc Kafka down |
| `outbox_failed_count` (status=FAILED) | > 0 → cần người xử lý |
| `outbox_publish_latency` p99 | > 2s |

Kết quả: kịch bản "DB commit xong, app crash trước khi publish" giờ chỉ làm event **đến trễ** (khi app/instance khác lên lại và poll), **không mất**.

