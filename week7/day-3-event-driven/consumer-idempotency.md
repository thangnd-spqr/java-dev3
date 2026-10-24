# Consumer Idempotency – 3 consumer cho `OrderConfirmed`

## 1. Vì sao phải idempotent?

Kafka + Spring Cloud Stream mặc định là **at-least-once**: consumer xử lý xong rồi mới commit offset. Duplicate xuất hiện khi:

| Nguồn duplicate | Ví dụ |
|---|---|
| Outbox publisher crash sau Kafka ack, trước `markPublished` | Cùng `eventId` được publish 2 lần |
| Consumer xử lý xong, crash trước commit offset | Restart đọc lại từ offset cũ |
| Rebalance consumer group (deploy, scale, `max.poll.interval.ms` vượt) | Partition chuyển sang instance khác, xử lý lại batch chưa commit |
| Retry của binder sau khi side effect đã chạy một phần | Email gửi xong, ghi DB lỗi → retry gửi lại |
| Replay DLQ / reset offset | Chủ động phát lại |

→ **Idempotent consumer**: xử lý cùng `eventId` N lần cho kết quả giống 1 lần.

## 2. Spring Cloud Stream – functional model & binding

| Bean | Vai trò | Binding |
|---|---|---|
| `Supplier<T>` | Nguồn phát message (binder poll định kỳ) | `xxx-out-0` |
| `Function<I,O>` | Nhận → biến đổi → phát tiếp | `xxx-in-0`, `xxx-out-0` |
| `Consumer<T>` | Nhận và xử lý cuối | `xxx-in-0` |
| `StreamBridge` | Gửi tùy ý từ code imperative (outbox publisher) | tên binding động |

```gradle
dependencyManagement {
    imports { mavenBom "org.springframework.cloud:spring-cloud-dependencies:2023.0.1" } // Boot 3.2.x
}
dependencies {
    implementation 'org.springframework.cloud:spring-cloud-stream-binder-kafka'
}
```

```yaml
spring:
  cloud:
    function:
      definition: notificationConsumer;auditConsumer;analyticsConsumer
    stream:
      kafka:
        binder:
          brokers: localhost:9092
          auto-create-topics: true
        bindings:
          notificationConsumer-in-0:
            consumer:
              enable-dlq: true
              dlq-name: order.events.v1.notification.dlq
          auditConsumer-in-0:
            consumer:
              enable-dlq: true
              dlq-name: order.events.v1.audit.dlq
          analyticsConsumer-in-0:
            consumer:
              enable-dlq: true
              dlq-name: order.events.v1.analytics.dlq
      bindings:
        notificationConsumer-in-0:
          destination: order.events.v1         # topic
          group: notification-service          # consumer group
          consumer:
            max-attempts: 4                    # 1 lần đầu + 3 retry
            back-off-initial-interval: 1000
            back-off-multiplier: 2.0
            back-off-max-interval: 10000
            concurrency: 3                     # 3 thread, mỗi thread ≥1 partition
        auditConsumer-in-0:
          destination: order.events.v1
          group: audit-service
          consumer:
            max-attempts: 4
            back-off-initial-interval: 1000
            back-off-multiplier: 2.0
            back-off-max-interval: 10000
        analyticsConsumer-in-0:
          destination: order.events.v1
          group: analytics-service
          consumer:
            max-attempts: 4
            back-off-initial-interval: 1000
            back-off-multiplier: 2.0
```

- **Destination** = topic `order.events.v1`; **binding** = cầu nối giữa function bean và destination.
- **3 group khác nhau** → mỗi consumer đều nhận mọi event. Thêm instance cùng group → chia partition.
- `max-attempts`/`back-off-*` = retry **in-memory** trong binder (không commit offset trong lúc retry). Hết retry → binder publish record gốc sang `dlq-name` kèm header lỗi rồi commit offset → partition không bị kẹt.
- Exception không nên retry (payload sai schema, validation) → khai báo để vào DLQ ngay:

```yaml
        auditConsumer-in-0:
          consumer:
            retryable-exceptions:
              com.example.consumer.NonRetryableEventException: false
              com.fasterxml.jackson.core.JsonProcessingException: false
```

## 3. Bảng dedupe dùng chung

```sql
CREATE TABLE processed_events (
    consumer_group VARCHAR(64)  NOT NULL,      -- 'notification-service' | 'audit-service' | 'analytics-service'
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(128) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);
-- Giữ ≥ retention topic + thời gian giữ DLQ (30 ngày) rồi mới xóa,
-- nếu không replay DLQ sẽ bị xử lý lại.
```

Khóa là `(consumer_group, event_id)` vì cùng một event phải được **từng** consumer xử lý đúng một lần.

```java
@Repository
@RequiredArgsConstructor
public class ProcessedEventRepository {
    private final JdbcTemplate jdbc;

    /** @return true nếu đây là lần đầu (claim thành công), false nếu đã xử lý trước đó */
    public boolean tryMarkProcessed(String group, UUID eventId, String eventType) {
        int rows = jdbc.update("""
            INSERT INTO processed_events (consumer_group, event_id, event_type)
            VALUES (?, ?, ?)
            ON CONFLICT (consumer_group, event_id) DO NOTHING
            """, group, eventId, eventType);
        return rows == 1;
    }
}
```

Dùng `INSERT ... ON CONFLICT DO NOTHING` thay cho `SELECT exists` rồi `INSERT` → không có race khi 2 instance (sau rebalance) cùng xử lý một event.

## 4. Audit Consumer – side effect nằm trong DB → dedupe cùng transaction

```java
@Configuration
public class AuditConsumerConfig {

    @Bean
    public Consumer<Message<EventEnvelope<OrderConfirmedPayload>>> auditConsumer(AuditHandler handler) {
        return handler::handle;
    }
}

@Component
@RequiredArgsConstructor
@Slf4j
class AuditHandler {
    private final ProcessedEventRepository processed;
    private final AuditRepository audits;

    @Transactional
    public void handle(Message<EventEnvelope<OrderConfirmedPayload>> msg) {
        var e = msg.getPayload();
        Integer attempt = msg.getHeaders().get("deliveryAttempt", Integer.class);
        log.info("AUDIT_RECEIVED eventId={} orderId={} attempt={}", e.eventId(), e.aggregateId(), attempt);

        if (!processed.tryMarkProcessed("audit-service", e.eventId(), e.eventType())) {
            log.info("AUDIT_DUPLICATE_SKIPPED eventId={}", e.eventId());
            return;
        }
        audits.insert(AuditRecord.from(e));   // cùng transaction với processed_events
    }
}
```

Mark và ghi audit **commit cùng nhau** → hoặc cả hai, hoặc không gì. Exception → rollback cả dòng `processed_events` → retry chạy lại sạch sẽ. Thêm lớp bảo vệ: `audit_records.event_id UNIQUE`.

## 5. Notification Consumer – side effect bên ngoài (email) → không gửi duplicate

Email **không** rollback được, nên không thể "gửi email trong transaction". Dùng bảng trạng thái + idempotency key của email provider:

```sql
CREATE TABLE notification_log (
    event_id      UUID         PRIMARY KEY,           -- 1 event -> tối đa 1 email
    order_id      VARCHAR(64)  NOT NULL,
    recipient     VARCHAR(255) NOT NULL,
    template      VARCHAR(64)  NOT NULL,              -- 'order-confirmed'
    status        VARCHAR(16)  NOT NULL,              -- SENDING | SENT | FAILED
    provider_message_id VARCHAR(128),
    attempts      INT          NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- chống trường hợp 2 event khác ID cho cùng một order (bug upstream)
CREATE UNIQUE INDEX uq_notification_order_template ON notification_log(order_id, template);
```

```java
@Component
@RequiredArgsConstructor
@Slf4j
class NotificationHandler {
    private final NotificationLogRepository logs;
    private final EmailClient email;

    public void handle(Message<EventEnvelope<OrderConfirmedPayload>> msg) {
        var e = msg.getPayload();

        // B1: claim (transaction ngắn, riêng)
        ClaimResult claim = logs.claim(e.eventId(), e.aggregateId(), e.payload().customerEmail());
        switch (claim) {
            case ALREADY_SENT -> { log.info("EMAIL_DUPLICATE_SKIPPED eventId={}", e.eventId()); return; }
            case NEW, RETRY_SENDING -> { /* tiếp tục */ }
        }

        // B2: gửi email với idempotency key = eventId
        //     provider (SES/SendGrid/Mailgun nội bộ) nhận key trùng sẽ không gửi lần 2
        String providerId = email.send(EmailRequest.orderConfirmed(e.payload()),
                                       /* idempotencyKey */ e.eventId().toString());

        // B3: đánh dấu SENT
        logs.markSent(e.eventId(), providerId);
        log.info("EMAIL_SENT eventId={} orderId={} providerId={}", e.eventId(), e.aggregateId(), providerId);
    }
}
```

`claim()`:

```sql
INSERT INTO notification_log (event_id, order_id, recipient, template, status, attempts)
VALUES (:eventId, :orderId, :recipient, 'order-confirmed', 'SENDING', 1)
ON CONFLICT (event_id) DO UPDATE
   SET attempts = notification_log.attempts + 1, updated_at = now()
   WHERE notification_log.status <> 'SENT'
RETURNING status;
-- không trả row  -> đã SENT -> ALREADY_SENT
-- trả 'SENDING' với attempts=1 -> NEW ; attempts>1 -> RETRY_SENDING
```

| Crash tại | Lần xử lý lại | Kết quả |
|---|---|---|
| Trước B1 | Claim mới | Gửi 1 lần |
| Sau B1, trước B2 | `SENDING` → gửi | Gửi 1 lần |
| Sau B2, trước B3 | `SENDING` → gửi lại **cùng idempotency key** → provider trả lại `providerId` cũ, không gửi | Gửi 1 lần ✅ |
| Sau B3 | `ALREADY_SENT` → skip | Gửi 1 lần |

> Nếu provider **không** hỗ trợ idempotency key, cửa sổ "sau B2, trước B3" là không thể đóng hoàn toàn; khi đó chọn **at-most-once cho email** (`SENDING` quá 10 phút → `FAILED` + alert thay vì gửi lại) hoặc chấp nhận hiếm hoi trùng. Đây là quyết định nghiệp vụ, phải ghi rõ.

## 6. Analytics Consumer – tránh "cộng hai lần"

Sai: `UPDATE daily_sales SET revenue = revenue + :amount` khi nhận event → duplicate = doanh thu gấp đôi.

Đúng (chọn 1):

1. **Dedupe + increment cùng transaction** (như Audit):
   ```sql
   -- trong 1 transaction
   INSERT INTO processed_events ('analytics-service', :eventId, 'OrderConfirmed') ON CONFLICT DO NOTHING;  -- 0 row -> skip
   INSERT INTO daily_sales(day, orders, revenue) VALUES (:day, 1, :amount)
   ON CONFLICT (day) DO UPDATE SET orders = daily_sales.orders + 1, revenue = daily_sales.revenue + EXCLUDED.revenue;
   ```
2. **Ghi fact theo khóa tự nhiên rồi aggregate** (idempotent tự nhiên):
   ```sql
   INSERT INTO fact_order_confirmed(order_id, confirmed_at, amount) VALUES (...)
   ON CONFLICT (order_id) DO NOTHING;
   -- dashboard = SELECT date(confirmed_at), count(*), sum(amount) ... (hoặc materialized view)
   ```

Dùng `aggregateVersion` để bỏ qua event cũ đến muộn nếu analytics lưu "trạng thái hiện tại" của order.

## 7. Thực hành: chủ động làm Audit Consumer lỗi

```java
@Component
@ConfigurationProperties("chaos.audit")
@Data
class AuditChaos { private boolean failAlways; private String failOrderPrefix = "ord-fail"; }

// trong AuditHandler.handle(), trước tryMarkProcessed:
if (chaos.isFailAlways() || e.aggregateId().startsWith(chaos.getFailOrderPrefix())) {
    throw new IllegalStateException("Simulated audit DB outage for " + e.aggregateId());
}
```

Gửi thử:

```bash
# 1. event bình thường
kafka-console-producer --bootstrap-server localhost:9092 --topic order.events.v1 \
  --property parse.key=true --property key.separator='|' < ord-1001.txt
# 2. event gây lỗi (aggregateId = ord-fail-01)
# 3. gửi LẠI đúng message ord-1001 (cùng eventId) để test duplicate
```

### Log quan sát được

```text
10:31:02.114 INFO  NOTIFY  EMAIL_SENT eventId=0b9f6c1e… orderId=ord-1001 providerId=msg-881
10:31:02.120 INFO  AUDIT   AUDIT_RECEIVED eventId=0b9f6c1e… orderId=ord-1001 attempt=1
10:31:02.131 INFO  ANALYT  ANALYTICS_RECORDED eventId=0b9f6c1e… revenue+=1250000

# duplicate (gửi lại cùng eventId)
10:31:20.402 INFO  NOTIFY  EMAIL_DUPLICATE_SKIPPED eventId=0b9f6c1e…
10:31:20.405 INFO  AUDIT   AUDIT_DUPLICATE_SKIPPED eventId=0b9f6c1e…
10:31:20.409 INFO  ANALYT  ANALYTICS_DUPLICATE_SKIPPED eventId=0b9f6c1e…

# event lỗi: ord-fail-01  (max-attempts=4, backoff 1s -> 2s -> 4s)
10:32:00.010 INFO  AUDIT   AUDIT_RECEIVED eventId=7c41…  orderId=ord-fail-01 attempt=1
10:32:00.012 WARN  AUDIT   IllegalStateException: Simulated audit DB outage for ord-fail-01
10:32:01.013 INFO  AUDIT   AUDIT_RECEIVED eventId=7c41…  attempt=2
10:32:03.015 INFO  AUDIT   AUDIT_RECEIVED eventId=7c41…  attempt=3
10:32:07.018 INFO  AUDIT   AUDIT_RECEIVED eventId=7c41…  attempt=4
10:32:07.020 ERROR o.s.c.s.b.k.KafkaMessageChannelBinder : Sending to DLQ topic order.events.v1.audit.dlq
10:32:07.035 INFO  AUDIT   (offset committed, partition tiếp tục với message kế tiếp)

# Notification & Analytics xử lý ord-fail-01 bình thường – lỗi của Audit không ảnh hưởng group khác
```

Kiểm tra DLQ:

```bash
kafka-console-consumer --bootstrap-server localhost:9092 --topic order.events.v1.audit.dlq \
  --from-beginning --property print.key=true --property print.headers=true
```

```text
x-original-topic:order.events.v1,x-original-partition:2,x-original-offset:118,
x-exception-fqcn:java.lang.IllegalStateException,
x-exception-message:Simulated audit DB outage for ord-fail-01,
x-exception-stacktrace:…,eventId:7c41…,eventType:OrderConfirmed
ord-fail-01  {"eventId":"7c41…","eventType":"OrderConfirmed",…}
```

Kiểm tra DB:

```sql
SELECT count(*) FROM notification_log WHERE event_id = '0b9f6c1e-…';   -- 1
SELECT count(*) FROM audit_records    WHERE event_id = '0b9f6c1e-…';   -- 1
SELECT count(*) FROM processed_events WHERE event_id = '7c41…' AND consumer_group = 'audit-service'; -- 0 (đã rollback)
```

## 8. Test tự động (Spring Cloud Stream test binder)

```java
@SpringBootTest
@Import(TestChannelBinderConfiguration.class)
class NotificationIdempotencyTest {
    @Autowired InputDestination input;
    @MockBean EmailClient email;

    @Test
    void sameEventIdTwice_sendsEmailOnce() {
        Message<byte[]> msg = MessageBuilder.withPayload(json("ord-1001", EVENT_ID)).build();
        input.send(msg, "order.events.v1");
        input.send(msg, "order.events.v1");

        verify(email, times(1)).send(any(), eq(EVENT_ID.toString()));
    }
}
```

## 9. Checklist idempotency

- [x] Dedupe theo `eventId` (không theo offset – offset thay đổi khi replay/republish).
- [x] Khóa dedupe theo `(consumer_group, event_id)`.
- [x] Insert-or-skip nguyên tử (`ON CONFLICT DO NOTHING`), không check-then-insert.
- [x] Side effect DB + mark processed trong **cùng transaction**.
- [x] Side effect ngoài (email) dùng state machine + idempotency key của provider.
- [x] Analytics không cộng dồn mù; dùng dedupe hoặc fact table theo khóa tự nhiên.
- [x] Thời gian giữ `processed_events` ≥ retention topic + DLQ.

