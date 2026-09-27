# Assignment 2 — Trả lời câu hỏi

## Q1: Vẽ lại flow của bug, chỉ ra bước nào gây ra vấn đề

```
User                App Server          Primary DB         Replica 2
 │                      │                   │                  │
 ├─ Đổi mật khẩu ──────►                   │                  │
 │                      ├── WRITE (pw mới) ─►                  │
 │                      │                   ├── async replicate ──► (chưa tới)
 │                      ◄── 200 OK ─────────┤                  │
 │◄── "Thành công" ─────┤                   │                  │
 │                      │                   │                  │
 │  (0.5s sau)          │                   │                  │
 ├─ Đăng nhập ──────────►                   │                  │
 │                      ├── READ (load balancer round-robin) ──►
 │                      │                   │                  ├─ check pw CŨ
 │                      ◄──────────────────────────── FAIL ────┤
 │◄── "Sai mật khẩu" ──┤                   │                  │
```

**Bước gây ra vấn đề:** Bước đăng nhập (read) bị load balancer route sang Replica 2 — nơi **chưa kịp nhận** bản ghi mật khẩu mới do replication lag (1-3s) > thời gian user đăng nhập lại (0.5s).

---

## Q2: Lỗi ở tầng nào?

**Lỗi ở tầng Architecture Design.**

- **Không phải Application code:** Code đổi mật khẩu và đăng nhập đều hoạt động đúng logic.
- **Không phải Database:** Database replicate đúng cơ chế async đã cấu hình.
- **Architecture design** mới là vấn đề: hệ thống chọn async replication để giảm latency nhưng **thiếu routing strategy** phân biệt request nào cần đọc từ Primary (data fresh) và request nào chấp nhận đọc từ Replica (stale data OK). Load balancer round-robin "mù quáng" là nguyên nhân gốc.

---

## Q3: Consistency model nào bị vi phạm?

**Read-your-writes consistency** bị vi phạm: user vừa ghi (đổi password) nhưng đọc lại ngay (đăng nhập) không thấy kết quả write của chính mình.

Hệ thống **không sai** — đây là **trade-off đã biết trước** của async replication. Async replication chỉ đảm bảo **Eventual Consistency** (dữ liệu sẽ đồng bộ *cuối cùng*), không đảm bảo read-your-writes. Vấn đề là team chưa xử lý trade-off này cho những use case nhạy cảm (authentication).

---

## Q4: Đề xuất ít nhất 3 giải pháp

| # | Giải pháp | Mô tả | Ưu điểm | Nhược điểm |
|---|-----------|--------|----------|------------|
| 1 | **Read-from-Primary sau write** | Sau khi user đổi mật khẩu, các request đọc liên quan (login) trong khoảng thời gian ngắn (vd: 5s) được route về Primary | Đơn giản implement, đảm bảo read-your-writes | Tăng tải lên Primary, cần cơ chế tracking user nào vừa write (session flag, cookie, sticky routing) |
| 2 | **Synchronous replication cho bảng nhạy cảm** | Cấu hình sync replication (hoặc semi-sync) riêng cho bảng `users/credentials` | Đảm bảo consistency mạnh cho dữ liệu critical | Tăng write latency, phức tạp cấu hình, nếu replica lag → write bị block |
| 3 | **Causal consistency token** | Sau write, server trả về một token (vd: LSN — Log Sequence Number). Request đọc tiếp theo gửi kèm token, replica chỉ phục vụ khi đã replicate đến LSN đó, nếu chưa thì fallback về Primary | Chính xác, không phải luôn đọc Primary, scalable | Phức tạp implement, cần client và server phối hợp, tăng latency nếu replica chưa kịp |
|

---

## Q5: Giải pháp chọn cho production và trade-off

**Chọn: Giải pháp 1 — Read-from-Primary sau write**, kết hợp cơ chế session flag.

**Cách implement:**
- Khi user thực hiện write nhạy cảm (đổi mật khẩu, update profile), set một flag vào session/cookie với TTL = 5s.
- Middleware kiểm tra: nếu flag tồn tại → route read về Primary; nếu không → route bình thường sang Replica.

**Trade-off:**
- **Tăng tải Primary** trong khoảng thời gian ngắn sau write — chấp nhận được vì chỉ áp dụng cho write nhạy cảm (đổi password không xảy ra thường xuyên).
- **Cần maintain thêm logic routing** ở tầng middleware/load balancer.
- **Không giải quyết 100%** cho trường hợp multi-device (user đổi password trên thiết bị A, đăng nhập trên thiết bị B) — nhưng đây là edge case ít phổ biến hơn.

Giải pháp này cân bằng tốt giữa **độ phức tạp thấp** và **hiệu quả cao** cho bài toán thực tế.
