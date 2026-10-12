# 04 — Reactive Fundamentals: Mono, Flux & Backpressure

## 1. Mono vs Flux

| Type | Số item | Ví dụ HR/Payroll |
| --- | --- | --- |
| `Mono<T>` | 0..1 rồi complete/error | `Mono<Employee> findById(id)` |
| `Flux<T>` | 0..N (có thể vô hạn) | `Flux<EmployeeChangedEvent> events()` |

- Cả hai đều là `Publisher` (Reactive Streams) và **lazy**: chưa `subscribe()` thì chưa chạy gì.
- `Flux` **không** có nghĩa là load hết data vào memory: item được đẩy **từng cái theo demand** (`request(n)`), xử lý xong có thể GC ngay. `collectList()` mới là thứ gom hết vào RAM.
- **Non-blocking ≠ chạy nhanh hơn**: 1 query DB vẫn mất 50ms. Khác biệt là thread **không đứng chờ** I/O mà đi phục vụ việc khác → ít thread hơn, scale ổn định hơn khi nhiều I/O chậm. CPU-bound thì không lợi gì.

```java
Mono<Employee> emp = repo.findById(id)
    .timeout(Duration.ofSeconds(2))
    .retryWhen(Retry.backoff(3, Duration.ofMillis(200)));

Flux<PayslipDto> slips = repo.findByPeriod(period)   // stream từng row
    .filter(p -> p.status() == APPROVED)
    .map(PayslipDto::from)
    .flatMap(mailer::send, 16);                      // tối đa 16 concurrent
```

## 2. Flow Producer → Consumer → Backpressure

```
 Producer (Publisher)            Subscription              Consumer (Subscriber)
 Payroll Event Service  ──────── onNext(event) ────────▶  Audit Consumer
   50,000 evt/s          ◀─────── request(n) ──────────    5,000 evt/s
                           (demand: "tôi chỉ nhận n")
        │
        ▼ vượt demand → áp dụng policy
   ┌──────────┬──────────┬──────────┬──────────────────┐
   │  BUFFER  │   DROP   │  LATEST  │ QUEUE bền (Kafka)│
   └──────────┴──────────┴──────────┴──────────────────┘
```

Backpressure = **downstream báo upstream** lượng nó xử lý được (`request(n)`); upstream chỉ phát tối đa `n` item.

```java
eventFlux
    .onBackpressureBuffer(10_000, e -> log.warn("overflow {}", e.id()),
                          BufferOverflowStrategy.ERROR)
    .limitRate(500)                                  // xin theo lô 500
    .flatMap(auditService::save, 8)
    .subscribe();
```

## 3. Scenario: 50,000 evt/s vs Audit 5,000 evt/s

Chênh lệch **45,000 evt/s** → sau 1 phút dồn **2.7 triệu** event.

### 3.1 Rủi ro nếu không có backpressure/buffering policy

- **Unbounded queue phình to → OOM**, service crash và **mất toàn bộ event đang nằm trong RAM**.
- **Latency tăng dần**: audit record ghi trễ vài phút → vài giờ, không còn real-time.
- **GC pause dài**, CPU bị GC ăn → producer cũng chậm theo (cascading failure).
- **Mất audit trail** → vi phạm compliance (SOX, luật lao động, thuế) – không chứng minh được ai sửa lương.
- Retry mù quáng khi consumer quá tải → **retry storm**, càng tệ hơn.

### 3.2 Khi nào Drop / Buffer / Retry / Queue

| Chiến lược | Dùng khi | Ví dụ |
| --- | --- | --- |
| **Drop / Latest** | Data mất được, chỉ cần giá trị mới nhất | Metrics, dashboard realtime, "employee đang online" |
| **Buffer (bounded)** | Burst ngắn, tốc độ trung bình consumer theo kịp | Spike 10s lúc chạy payroll; buffer có giới hạn + alert khi đầy |
| **Retry (backoff + jitter)** | Lỗi **tạm thời** (timeout, DB lock), consumer idempotent | Ghi audit DB lỗi transient; giới hạn số lần → DLQ |
| **Queue bền (Kafka/RabbitMQ)** | Chênh lệch **kéo dài**, không được mất data | Đúng scenario này: 50k vs 5k |

### 3.3 Chiến lược đề xuất cho Audit

1. Producer publish vào **Kafka topic** (durable, partition theo `tenantId/employeeId` để giữ thứ tự).
2. Audit consumer **pull** theo khả năng (pull = backpressure tự nhiên), commit offset sau khi ghi DB thành công.
3. **Scale out**: 10 partitions × consumer 5k/s ≈ 50k/s; **batch insert** (500 record/lần) để tăng throughput.
4. Consumer **idempotent** (unique `eventId`) → retry/re-deliver không tạo bản ghi trùng.
5. Lỗi vĩnh viễn → **DLQ** + alert; giám sát **consumer lag**, auto-scale khi lag vượt ngưỡng.
6. Bên trong consumer dùng `limitRate` + `onBackpressureBuffer(bounded, ERROR)` — **không** dùng `onBackpressureDrop`.

### 3.4 Data KHÔNG được drop

- Thay đổi **lương, thưởng, khấu trừ, thuế, bảo hiểm**.
- Thay đổi **trạng thái nhân viên** (hire, terminate, promotion), **bank account**.
- **Phân quyền / role** và hành động **approve/run payroll**.
- Mọi event phục vụ **audit/compliance** (ai, sửa gì, lúc nào, giá trị cũ → mới).

→ Những data này: **at-least-once + idempotent**, lưu bền trước khi ACK. Chỉ được drop: metrics, heartbeat, UI notification trùng lặp.

## 4. Checklist

- [x] Non-blocking ≠ nhanh hơn: tiết kiệm thread khi chờ I/O.
- [x] `Flux` stream theo demand, không load hết vào memory.
- [x] Giải thích backpressure bằng scenario Payroll → Audit.
- [x] Chiến lược khi consumer chậm: durable queue + scale out + batch + DLQ.

