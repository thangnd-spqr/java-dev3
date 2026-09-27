# Sharding Basics

## 1. So sánh Replication và Sharding

| Tiêu chí | Replication | Sharding |
|---|---|---|
| **Dữ liệu** | Cùng dữ liệu được copy sang nhiều node | Dữ liệu được chia thành các phần khác nhau trên các node |
| **Mục tiêu chính** | High availability, Read scaling | Write scaling, Storage scaling |
| **Write** | Chỉ ghi vào Primary → không scale write | Ghi phân tán vào nhiều shard → scale write |
| **Read** | Đọc từ nhiều replica → scale read | Đọc nhanh nếu query đúng shard, chậm nếu cross-shard |
| **Fault tolerance** | Replica thay thế khi Primary chết | Mất 1 shard = mất 1 phần dữ liệu (cần kết hợp replication) |
| **Độ phức tạp** | Thấp – cấu hình replica | Cao – chọn shard key, routing, rebalance |

## 2. Sơ đồ 3 Shards (bảng `orders`, shard key = `customerId`)

```
                    ┌──────────────┐
                    │ Shard Router │
                    └──────┬───────┘
                           │
            ┌──────────────┼──────────────┐
            ▼              ▼              ▼
    ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
    │   Shard 1    │ │   Shard 2    │ │   Shard 3    │
    │              │ │              │ │              │
    │ customerId   │ │ customerId   │ │ customerId   │
    │ 1 – 1,000,000│ │ 1,000,001 –  │ │ 2,000,001 –  │
    │              │ │  2,000,000   │ │  3,000,000   │
    │ orders of    │ │ orders of    │ │ orders of    │
    │ those custs  │ │ those custs  │ │ those custs  │
    └──────────────┘ └──────────────┘ └──────────────┘
```

Shard Router nhận request → dựa vào `customerId` → xác định shard đích → chuyển query đến đúng shard.

## 3. Phân tích các Shard Key ứng viên

### Các shard key ứng viên cho bảng `orders`:

| Shard Key | Ưu điểm | Nhược điểm |
|---|---|---|
| `orderId` | Phân phối đều dữ liệu | Query theo customer phải scatter tất cả shard |
| `customerId` | Query theo customer chỉ hit 1 shard, tạo order cho customer cũng 1 shard | Có thể lệch nếu có big customer |
| `createdAt` | Tốt cho báo cáo theo thời gian | Shard mới nhất luôn nhận hết write → hotspot |
| `countryCode` | Tốt cho query theo quốc gia | Phân phối không đều (nước lớn vs nước nhỏ) |

### Phân tích theo từng query:

| Query | `orderId` | `customerId` | `createdAt` | `countryCode` |
|---|---|---|---|---|
| Lấy tất cả orders của 1 customer | ❌ Scatter all shards | ✅ Single shard | ❌ Scatter all shards | ❌ Scatter all shards |
| Tạo order cho customer | ⚠️ 1 shard (theo orderId) | ✅ Single shard | ⚠️ Hotspot shard mới nhất | ⚠️ 1 shard (theo country) |
| Tìm order theo orderId | ✅ Single shard | ⚠️ Cần biết customerId hoặc scatter | ❌ Scatter all shards | ❌ Scatter all shards |
| Báo cáo doanh thu theo tháng | ❌ Scatter all shards | ❌ Scatter all shards | ✅ Ít shard (theo range tháng) | ❌ Scatter all shards |

## 4. Lựa chọn Shard Key & Giải thích

### ✅ Chọn: `customerId`

**Lý do:**

1. **Phù hợp query phổ biến nhất**: Phần lớn thao tác nghiệp vụ (tạo order, xem orders của customer) đều xoay quanh `customerId` → chỉ hit đúng 1 shard.
2. **Phân phối dữ liệu tương đối đều**: Số lượng customer lớn → dữ liệu phân bổ đều giữa các shard (trừ trường hợp big customer, có thể xử lý bằng consistent hashing).
3. **Phân phối traffic đều**: Mỗi customer tạo traffic trên shard riêng → không tạo hotspot.
4. **Hạn chế cross-shard query**: Chỉ query báo cáo doanh thu toàn hệ thống mới cần scatter, nhưng đây là query analytics chạy không thường xuyên, có thể dùng CQRS hoặc batch job riêng.

**Trade-off chấp nhận được:**
- Tìm order theo `orderId` cần thêm bước tra `customerId` (giải quyết bằng lookup table hoặc encode `customerId` vào `orderId`).
- Báo cáo toàn hệ thống cần scatter nhưng tần suất thấp, có thể offload sang hệ thống analytics riêng.
