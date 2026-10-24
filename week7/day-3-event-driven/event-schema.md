# Event Schema – OrderConfirmed

## 1. Command vs Event

| | Command | Event |
|---|---|---|
| Ý nghĩa | **Yêu cầu** một service làm gì đó | **Thông báo** một sự thật **đã xảy ra** |
| Thì / đặt tên | Động từ mệnh lệnh: `ReserveInventory`, `ChargePayment` | Quá khứ: `OrderCreated`, `InventoryReserved`, `PaymentSucceeded`, `OrderConfirmed` |
| Số người nhận | **Đúng 1** handler (owner của nghiệp vụ) | **0..N** subscriber, publisher không biết ai nghe |
| Có thể bị từ chối? | Có (hết hàng, thẻ bị từ chối) → có kết quả thành công/thất bại | Không – đã xảy ra rồi, chỉ có thể phát event bù (`OrderCancelled`) |
| Coupling | Sender biết receiver và kỳ vọng kết quả | Publisher không phụ thuộc consumer |
| Topic ví dụ | `inventory.commands.v1` | `order.events.v1` |

```text
Order Service ──ReserveInventory (command)──▶ Inventory Service
Inventory     ──InventoryReserved (event)───▶ ai quan tâm thì nghe (Order, Analytics…)
Order Service ──ChargePayment (command)─────▶ Payment Service
Payment       ──PaymentSucceeded (event)────▶ Order Service
Order Service ──OrderConfirmed (event)──────▶ Notification, Audit, Analytics
```

> Quy tắc: nếu thêm một consumer mới mà **phải sửa publisher** → đó đang là command trá hình. `OrderConfirmed` là event: thêm Analytics Consumer không cần đụng Order Service.

## 2. Event envelope

Mọi event dùng chung envelope; phần nghiệp vụ nằm trong `payload`.

| Field | Kiểu | Bắt buộc | Mô tả |
|---|---|---|---|
| `eventId` | UUID (string) | ✅ | ID duy nhất toàn cục, **sinh 1 lần khi ghi outbox**, giữ nguyên khi publish lại/replay → khóa dedupe của consumer |
| `eventType` | string | ✅ | `OrderConfirmed` – consumer route/filter theo field này |
| `aggregateType` | string | ✅ | `Order` |
| `aggregateId` | string | ✅ | `orderId`; đồng thời là **Kafka message key** → mọi event của 1 order vào cùng partition, giữ thứ tự |
| `aggregateVersion` | long | ✅ | Version của aggregate sau thay đổi (optimistic lock version). Consumer bỏ qua event cũ hơn version đã thấy |
| `occurredAt` | ISO-8601 UTC | ✅ | Thời điểm nghiệp vụ xảy ra (lúc commit), **không phải** lúc publish |
| `schemaVersion` | int | ✅ | Version của payload schema, tăng khi breaking change |
| `producer` | string | ✅ | `order-service` – truy vết nguồn |
| `correlationId` | string | ✅ | Trace xuyên suốt request (checkout) → log/trace |
| `causationId` | string | ⬜ | `eventId` của event/command gây ra event này (`PaymentSucceeded` → `OrderConfirmed`) |
| `payload` | object | ✅ | Dữ liệu nghiệp vụ |

### Ví dụ message trên Kafka

```text
topic   : order.events.v1
key     : ord-1001                       (aggregateId)
headers : eventId=0b9f6c1e-…, eventType=OrderConfirmed, schemaVersion=1,
          correlationId=chk-77ab, contentType=application/json
```

```json
{
  "eventId": "0b9f6c1e-5d2a-4a7e-9a51-3f0e2c8d9b11",
  "eventType": "OrderConfirmed",
  "aggregateType": "Order",
  "aggregateId": "ord-1001",
  "aggregateVersion": 4,
  "occurredAt": "2026-10-24T03:15:42.120Z",
  "schemaVersion": 1,
  "producer": "order-service",
  "correlationId": "chk-77ab",
  "causationId": "evt-payment-5521",
  "payload": {
    "orderId": "ord-1001",
    "customerId": "cus-42",
    "customerEmail": "an.nguyen@example.com",
    "status": "CONFIRMED",
    "currency": "VND",
    "totalAmount": 1250000,
    "items": [
      { "productId": "p-1", "quantity": 2, "unitPrice": 500000 },
      { "productId": "p-7", "quantity": 1, "unitPrice": 250000 }
    ],
    "confirmedAt": "2026-10-24T03:15:42.120Z"
  }
}
```

`eventId` được copy vào header để consumer có thể dedupe/log **trước khi** deserialize payload (hữu ích khi payload hỏng → vào DLQ vẫn biết eventId).

### Java record

```java
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        int schemaVersion,
        String producer,
        String correlationId,
        String causationId,
        T payload) {}

public record OrderConfirmedPayload(
        String orderId, String customerId, String customerEmail, String status,
        String currency, long totalAmount, List<Item> items, Instant confirmedAt) {
    public record Item(String productId, int quantity, long unitPrice) {}
}
```

### JSON Schema (rút gọn)

```json
{
  "$id": "order.events/OrderConfirmed/v1",
  "type": "object",
  "required": ["eventId","eventType","aggregateType","aggregateId","aggregateVersion",
               "occurredAt","schemaVersion","producer","correlationId","payload"],
  "properties": {
    "eventId":        { "type": "string", "format": "uuid" },
    "eventType":      { "const": "OrderConfirmed" },
    "aggregateType":  { "const": "Order" },
    "aggregateId":    { "type": "string" },
    "aggregateVersion": { "type": "integer", "minimum": 1 },
    "occurredAt":     { "type": "string", "format": "date-time" },
    "schemaVersion":  { "const": 1 },
    "payload": {
      "type": "object",
      "required": ["orderId","customerId","customerEmail","status","totalAmount","items","confirmedAt"],
      "properties": {
        "status": { "const": "CONFIRMED" },
        "totalAmount": { "type": "integer", "minimum": 0 }
      }
    }
  }
}
```

## 3. Quy tắc thiết kế & versioning

1. **Event là sự thật, bất biến** – không sửa event đã publish; sai thì phát event bù.
2. **Payload đủ dùng (event-carried state)** – kèm `customerEmail`, `totalAmount`, `items` để consumer không phải gọi ngược Order Service (tránh coupling + tránh đọc state mới hơn event).
3. **Không đưa dữ liệu nhạy cảm** (số thẻ, password). PII (email) chỉ ở topic có ACL, retention hợp lý.
4. **Backward-compatible changes** (thêm field optional) → giữ `schemaVersion`; consumer phải **bỏ qua field lạ** (`FAIL_ON_UNKNOWN_PROPERTIES=false`).
5. **Breaking change** (đổi tên/kiểu, xóa field) → `schemaVersion: 2`, publish song song v1 + v2 (hoặc topic `order.events.v2`) đến khi mọi consumer migrate.
6. Thời gian luôn UTC; tiền dùng số nguyên đơn vị nhỏ nhất (VND không có số lẻ) – không dùng `double`.

## 4. Topic & partition

| Topic | Key | Partitions | Retention | Ghi chú |
|---|---|---|---|---|
| `order.events.v1` | `aggregateId` | 6 | 7 ngày | Tất cả event của Order |
| `order.events.v1.notification.dlq` | giữ key gốc | 1 | 30 ngày | DLQ của Notification |
| `order.events.v1.audit.dlq` | giữ key gốc | 1 | 30 ngày | DLQ của Audit |
| `order.events.v1.analytics.dlq` | giữ key gốc | 1 | 30 ngày | DLQ của Analytics |

- Mỗi consumer là **một consumer group riêng** (`notification-service`, `audit-service`, `analytics-service`) → mỗi group nhận **đủ** mọi event (pub/sub), trong group thì các instance chia partition (scale out, tối đa = số partition).
- Key = `orderId` → `OrderConfirmed` không bao giờ bị xử lý trước `OrderCreated` của cùng order (thứ tự chỉ đảm bảo **trong một partition**).

