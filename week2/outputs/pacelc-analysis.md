# PACELC Analysis — Order Service

## 1. PACELC mở rộng CAP ở điểm nào

CAP chỉ nói về lúc **partition xảy ra** (chọn A hay C).
PACELC bổ sung câu hỏi cho trạng thái **bình thường** (Else): chọn **Latency** hay **Consistency**.

```
If Partition → chọn Availability hoặc Consistency   (giống CAP)
Else          → chọn Latency    hoặc Consistency   (PACELC bổ sung)
```

---

## 2. Sơ đồ so sánh Read

```
Read từ Primary (strong consistency):

  Client ──► Primary ──► trả data mới nhất
                │
                │ sync xong rồi
                ▼
             Replica

Read từ Replica (low latency):

  Client ──────────────► Replica ──► trả data (có thể CŨ)
                         │
            Primary ─ async ─┘  (chưa sync xong)

Read-after-write inconsistency:

  Client write ──► Primary (status = PAID)
                      │
                      │ async replication (chưa xong)
                      ▼
  Client read  ──► Replica (status = PENDING!)  ← stale data
```

---

## 3. Phân tích Scenario

| Scenario | Ưu tiên | Lý do |
|----------|:-------:|-------|
| User vừa thanh toán xong và xem trạng thái đơn | **Strong Consistency** | Vừa trả tiền mà thấy "PENDING" → user hoang mang, gọi support, thanh toán lại → thiệt hại |
| Trang danh sách sản phẩm | **Low Latency** | Data cũ vài giây không ảnh hưởng, trang load nhanh giữ chân user |
| Admin xem báo cáo doanh thu cuối ngày | **Strong Consistency** | Báo cáo tài chính cần chính xác, sai số dẫn đến quyết định sai |
| Kiểm tra số dư ví điện tử | **Strong Consistency** | Số dư sai → user rút/chi vượt mức → double spending, thiệt hại tài chính |

### User xem trạng thái đơn → EL/C (Consistency)
- **Yêu cầu:** Read-after-write phải nhất quán — write PAID thì read phải thấy PAID.
- **Cách làm:** Read từ primary, hoặc dùng read-your-writes consistency.
- **Nếu chọn sai (Low Latency):** User thấy status cũ → gọi support, thanh toán trùng.

### Trang danh sách sản phẩm → EL/L (Latency)
- **Yêu cầu:** Trang phải load nhanh, data cũ vài giây chấp nhận được.
- **Cách làm:** Read từ replica gần nhất, cache aggressive.
- **Nếu chọn sai (Consistency):** Trang load chậm → bounce rate tăng, mất doanh thu.

### Admin xem báo cáo doanh thu → EL/C (Consistency)
- **Yêu cầu:** Số liệu chính xác, không cần real-time.
- **Cách làm:** Query primary hoặc replica đã sync đầy đủ (lag = 0).
- **Nếu chọn sai (Low Latency):** Báo cáo thiếu giao dịch → quyết định kinh doanh sai.

### Kiểm tra số dư ví → EL/C (Consistency)
- **Yêu cầu:** Số dư phải chính xác tuyệt đối trước khi cho phép giao dịch.
- **Cách làm:** Read từ primary, kết hợp lock/serializable isolation.
- **Nếu chọn sai (Low Latency):** Số dư stale → cho rút vượt mức → double spending.

