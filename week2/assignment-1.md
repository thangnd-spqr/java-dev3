# **Assignment 1: Trả lời câu hỏi — Tại sao khách hàng thấy đơn hàng bị mất tiền 2 lần?**

**Topics áp dụng:** CAP Theorem, PACELC

---

## **Q1: Khi network partition xảy ra, hệ thống này đã ưu tiên C hay A? Vì sao?**

### Trả lời:

Hệ thống đã **ưu tiên Availability (A)** thay vì Consistency (C).

**Lý do nhận biết:**

- Khi network partition xảy ra (đứt kết nối Singapore ↔ Tokyo trong 45 giây), Replica ở Tokyo **vẫn tiếp tục nhận và xử lý write request** (thanh toán cho ORD123) thay vì từ chối request.
- Nếu hệ thống ưu tiên **Consistency (C)**, khi phát hiện mất kết nối với Primary, Replica Tokyo sẽ **từ chối mọi write request** và trả về lỗi cho user (ví dụ: HTTP 503 Service Unavailable) để đảm bảo không có dữ liệu nào bị ghi sai hoặc conflict.
- Nhưng thực tế, hệ thống đã quyết định **"cho phép replica tạm thời nhận write khi mất kết nối với Primary, để tránh downtime"** — đây chính là đặc trưng của một **AP system**: hy sinh tính nhất quán (Consistency) để đảm bảo tính khả dụng (Availability).

**Hậu quả của quyết định AP:**

- Primary (Singapore) vẫn giữ `ORD123 = PENDING`.
- Replica (Tokyo) đã ghi `ORD123 = PAID` và charge thẻ 500,000đ.
- Khi reconciliation xảy ra, 2 bản ghi conflict → merge sai → khách bị charge 2 lần.

---

## **Q2: Nếu thiết kế hệ thống Order/Payment này, chọn CP hay AP? Tại sao?**

### Trả lời:

Đối với hệ thống **Order/Payment**, cần chọn **CP (Consistency + Partition Tolerance)**.

**Lý do:**

1. **Payment là dữ liệu tài chính — không được phép sai:** Mỗi giao dịch thanh toán liên quan trực tiếp đến tiền thật của khách hàng. Một lần ghi sai có thể dẫn đến:
   - Charge tiền 2 lần (như incident trong đề bài)
   - Mất tiền không hoàn được
   - Mất uy tín, khách hàng kiện, vi phạm quy định tài chính

2. **Chi phí của "inconsistency" cao hơn chi phí của "unavailability":**
   - Nếu chọn CP: user có thể phải chờ hoặc thấy lỗi tạm thời trong 45 giây → khó chịu nhưng không mất tiền.
   - Nếu chọn AP: user không bị gián đoạn, nhưng có thể bị charge sai → hậu quả nghiêm trọng hơn nhiều.

3. **Nguyên tắc trong thiết kế hệ thống tài chính:** "It's better to be unavailable than to be wrong" — thà từ chối giao dịch còn hơn xử lý sai giao dịch.

4. **Theo PACELC:** Khi có Partition → chọn Consistency (PC). Khi không có Partition → vẫn nên ưu tiên Consistency hơn Latency (EC) cho dữ liệu payment → hệ thống nên là **PC/EC**.

---

## **Q3: Nếu chọn CP (từ chối request khi partition), trải nghiệm user sẽ như thế nào? Có chấp nhận được không?**

### Trả lời:

**Trải nghiệm user khi chọn CP:**

- User A ở Nhật bấm "Thanh toán" → hệ thống phát hiện không thể kết nối đến Primary (Singapore) → trả về lỗi:
  > *"Hệ thống đang tạm thời gián đoạn. Vui lòng thử lại sau ít phút."*
- User phải chờ khoảng **45 giây** (thời gian partition) rồi thử lại.
- Trong thời gian này, user **không mất tiền**, đơn hàng vẫn ở trạng thái `PENDING`.

**Đánh giá:**

| Khía cạnh | Phân tích |
|---|---|
| **Trải nghiệm ngắn hạn** | Hơi khó chịu — user phải chờ hoặc thử lại |
| **Tần suất xảy ra** | Rất hiếm — network partition giữa 2 region thường rất ít khi xảy ra |
| **Hậu quả nếu không chọn CP** | Mất tiền, hoàn tiền, incident report, mất uy tín |
| **Kết luận** | **Chấp nhận được** |

**Có chấp nhận được không?** → **CÓ**, hoàn toàn chấp nhận được vì:

- Partition hiếm khi xảy ra (vài phút/năm).
- User chỉ cần retry sau vài giây/phút.
- Việc "tạm thời không dùng được" dễ giải thích và xử lý hơn nhiều so với "bị mất tiền 2 lần".
- Có thể cải thiện UX bằng cách hiển thị thông báo rõ ràng và tự động retry.

---

## **Q4: Có cách nào vừa tránh mất tiền, vừa không làm user chờ đợi quá lâu không?**

### Trả lời:

**Có!** Có nhiều kỹ thuật kết hợp để đạt được cả hai mục tiêu:

### 1. Idempotency Key

- Mỗi request thanh toán được gắn một **idempotency key** duy nhất (ví dụ: `payment_ORD123_attempt_1`).
- Khi hệ thống reconcile sau partition, nếu thấy 2 bản ghi có cùng idempotency key → **chỉ giữ 1**, bản còn lại bị loại bỏ.
- Kết quả: dù ghi 2 lần, user chỉ bị charge **1 lần**.

```java
// Ví dụ: Idempotency Key trong Payment Request
@PostMapping("/payments")
public ResponseEntity<PaymentResponse> processPayment(
    @RequestHeader("Idempotency-Key") String idempotencyKey,
    @RequestBody PaymentRequest request) {
    
    // Kiểm tra idempotency key đã tồn tại chưa
    Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
        // Trả về kết quả cũ, KHÔNG charge lại
        return ResponseEntity.ok(existing.get().toResponse());
    }
    
    // Xử lý thanh toán mới
    Payment payment = paymentService.process(request, idempotencyKey);
    return ResponseEntity.ok(payment.toResponse());
}
```
---

## **Q5: Loại dữ liệu nào trong hệ thống E-commerce có thể chấp nhận AP? Cho ví dụ cụ thể.**

### Trả lời:

Nhiều loại dữ liệu trong E-commerce **không cần strong consistency** và có thể chấp nhận **AP (Eventual Consistency)** để đổi lấy tốc độ và availability tốt hơn:

### 1. Product Catalog (Thông tin sản phẩm)

- **Ví dụ:** Tên sản phẩm, mô tả, hình ảnh, thông số kỹ thuật.
- **Lý do AP được:** Nếu user ở Tokyo thấy mô tả sản phẩm cũ hơn 5 giây so với Singapore → không ảnh hưởng gì nghiêm trọng.
- **Chấp nhận:** User thấy phiên bản cũ vài giây → eventual consistency đủ tốt.

### 2. Product Reviews & Ratings (Đánh giá sản phẩm)

- **Ví dụ:** User viết review, rating trung bình cập nhật.
- **Lý do AP được:** Review mới hiển thị chậm vài giây/phút ở region khác → không ai bị thiệt hại.
- **Chấp nhận:** Một review mới xuất hiện sau 30 giây ở region khác → hoàn toàn OK.

### 3. Shopping Cart (Giỏ hàng)

- **Ví dụ:** User thêm/xóa sản phẩm trong giỏ hàng.
- **Lý do AP được:** Giỏ hàng là dữ liệu tạm, thuộc về 1 user, conflict thấp. Nếu mất → user thêm lại.
- **Chấp nhận:** Giỏ hàng hiển thị hơi chậm vài giây → không vấn đề.

### 4. User Activity / Browsing History (Lịch sử duyệt web)

- **Ví dụ:** "Sản phẩm đã xem gần đây", "Gợi ý cho bạn".
- **Lý do AP được:** Dữ liệu phục vụ recommendation, mất vài bản ghi không ảnh hưởng trải nghiệm.

### 5. Wishlist (Danh sách yêu thích)

- **Ví dụ:** User lưu sản phẩm yêu thích.
- **Lý do AP được:** Tương tự giỏ hàng — dữ liệu cá nhân, conflict thấp, hậu quả mất dữ liệu nhỏ.

### 6. Notification / Alert (Thông báo)

- **Ví dụ:** "Đơn hàng đã được giao", "Flash sale bắt đầu".
- **Lý do AP được:** Thông báo đến chậm vài giây → không vấn đề.

### 7. Search Index (Chỉ mục tìm kiếm)

- **Ví dụ:** Elasticsearch index cho tìm kiếm sản phẩm.
- **Lý do AP được:** Index cập nhật chậm vài giây → user có thể không thấy sản phẩm mới ngay, nhưng không gây thiệt hại.


