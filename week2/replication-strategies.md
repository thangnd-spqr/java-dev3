# Replication Strategies

---

## 1. Bảng so sánh các chiến lược Replication

| **Tiêu chí** | **Synchronous** | **Asynchronous** | **Semi-synchronous** |
| --- | --- | --- | --- |
| Write latency | Cao – phải chờ tất cả replica ACK | Thấp nhất – trả về ngay sau khi ghi local | Trung bình – chờ ít nhất 1 replica ACK |
| Data durability | Cao nhất – dữ liệu tồn tại trên nhiều node trước khi ACK | Thấp – chỉ đảm bảo trên primary tại thời điểm ACK | Khá cao – đảm bảo ít nhất 1 replica có dữ liệu |
| Replica lag | Không có lag (zero lag) | Có thể lag đáng kể | Lag thấp trên replica đã ACK, các replica còn lại có thể lag |
| Rủi ro data loss | Gần như không mất dữ liệu | Có rủi ro mất dữ liệu nếu primary chết trước khi replicate | Rủi ro thấp, chỉ mất nếu cả primary và replica đã ACK cùng chết |
| Use case phù hợp | Giao dịch tài chính, payment | Logging, analytics, dữ liệu chấp nhận mất | Đa số production system cần cân bằng giữa tốc độ và an toàn |

---

## 2. Phân tích Use Case

### 1. Payment transaction → **Synchronous Replication**
- Dữ liệu thanh toán không được phép mất. Cần đảm bảo durability tuyệt đối.
- Chấp nhận write latency cao để đổi lấy consistency và data safety.

### 2. Product catalog → **Semi-synchronous Replication**
- Dữ liệu quan trọng nhưng không nhạy cảm bằng payment.
- Cần đọc nhanh và chấp nhận lag nhỏ trên một số replica.

### 3. Application logs → **Asynchronous Replication**
- Volume lớn, write liên tục. Ưu tiên throughput và low latency.
- Mất một vài dòng log khi primary chết là chấp nhận được.

### 4. Notification delivery history → **Semi-synchronous Replication**
- Cần ghi nhận lịch sử gửi để tránh gửi trùng, nhưng không critical như payment.
- Semi-sync đảm bảo ít nhất 1 bản sao an toàn với latency hợp lý.

### 5. Analytics events → **Asynchronous Replication**
- Dữ liệu dùng cho phân tích, không yêu cầu real-time consistency.
- Ưu tiên write nhanh, chấp nhận mất một lượng nhỏ event.

---

## 3. Timeline minh họa

### Synchronous Write

```
t0: Client gửi write request → Primary
t1: Primary ghi dữ liệu vào local storage
t2: Primary gửi dữ liệu tới Replica
t3: Replica ghi dữ liệu vào storage
t4: Replica gửi ACK về Primary
t5: Primary gửi SUCCESS về Client
```

> Client chỉ nhận SUCCESS sau khi Replica đã xác nhận → đảm bảo dữ liệu tồn tại trên cả 2 node.

### Asynchronous Write

```
t0: Client gửi write request → Primary
t1: Primary ghi dữ liệu vào local storage
t2: Primary gửi SUCCESS về Client         ← Client nhận kết quả ngay
t3: Primary gửi dữ liệu tới Replica (background)
t4: Replica ghi dữ liệu vào storage
```

> Client nhận SUCCESS ngay tại t2, không cần chờ Replica → write nhanh nhưng có khoảng trống rủi ro (t2 → t4).

---

## 4. Case Data Loss của Asynchronous Replication

```
t0: Client gửi write "Order #123" → Primary
t1: Primary ghi "Order #123" vào local     ✓
t2: Primary gửi SUCCESS về Client           ✓ (Client tin rằng dữ liệu đã an toàn)
t3: Primary bắt đầu replicate sang Replica...
     ⚡ Primary CRASH tại t3 — trước khi Replica nhận được dữ liệu
t4: Replica được promote thành Primary mới
```

**Kết quả:** "Order #123" đã được ACK cho client nhưng **không tồn tại** trên Replica (nay là Primary mới) → **dữ liệu bị mất**.

**Data-loss window** = khoảng thời gian từ khi Primary ACK cho client (t2) đến khi Replica nhận và ghi xong dữ liệu. Bất kỳ write nào trong khoảng này sẽ mất nếu Primary chết.
