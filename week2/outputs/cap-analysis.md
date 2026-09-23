# CAP Theorem Analysis

## 1. Sơ đồ Network Partition

```
  Bình thường:                    Khi Partition xảy ra:

  ┌────────┐    sync    ┌────────┐    ┌────────┐  ╳ ╳ ╳  ┌────────┐
  │ Node A │ ◄────────► │ Node B │    │ Node A │  ╳ ✘ ╳  │ Node B │
  └────────┘            └────────┘    └────────┘  ╳ ╳ ╳  └────────┘
      │                     │             │                    │
  Client 1              Client 2     Client 1             Client 2
                                     (data mới)           (data CŨ!)
```

## 2. Ví dụ Read/Write khi Partition xảy ra

**Bài toán:** Chuyển 300,000 VND từ Account A (1,000,000) → Account B (500,000).

Write thành công tại Node A, nhưng partition khiến Node B không nhận được update:

| Account | Node A (đã cập nhật) | Node B (stale) |
|---------|---------------------|----------------|
| A       | **700,000 VND**     | 1,000,000 VND  |
| B       | **800,000 VND**     | 500,000 VND    |

→ Client 2 đọc từ Node B thấy Account A vẫn còn 1,000,000 → có thể rút thêm → **double spending**.

---

## 3. Phân tích 3 hệ thống

| System | CP/AP | Lý do |
|--------|:-----:|-------|
| Banking/Payment | **CP** | Sai lệch số dư = mất tiền, vi phạm pháp luật |
| Social-media news feed | **AP** | Feed cũ vài giây không ảnh hưởng, downtime mới gây mất user |
| E-commerce product catalog | **AP** | Catalog cần luôn truy cập được, giá/tồn kho xác nhận lại ở bước checkout |

### Banking/Payment → CP
- **Dữ liệu quan trọng:** Số dư, lịch sử giao dịch.
- **Khi partition:** Từ chối giao dịch write (chuyển/rút tiền), trả "Service unavailable".
- **Nếu chọn sai (AP):** Double spending, số dư âm, thiệt hại tài chính, vi phạm pháp luật.

### Social-media News Feed → AP
- **Dữ liệu quan trọng:** Bài viết, like/comment count.
- **Khi partition:** Cho phép tất cả — hiển thị feed cũ, vẫn cho đăng bài (sync sau).
- **Nếu chọn sai (CP):** Người dùng không truy cập được → mất engagement, giảm doanh thu quảng cáo.

### E-commerce Product Catalog → AP
- **Dữ liệu quan trọng:** Thông tin sản phẩm, giá, tồn kho.
- **Khi partition:** Cho phép duyệt/xem sản phẩm; tại bước checkout cần xác nhận lại giá & tồn kho (chuyển sang CP).
- **Nếu chọn sai (CP):** Trang không load được → bounce rate tăng, mất doanh thu.

