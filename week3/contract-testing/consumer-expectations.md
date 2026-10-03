# Consumer Expectations

## Order Service cần gì từ Product Service?

Order Service gọi `GET /api/products/{productId}` để **kiểm tra sản phẩm có tồn tại và còn hàng không** trước khi tạo đơn hàng. Do đó, consumer chỉ cần đúng 4 field:

| Field | Lý do cần |
|-------|-----------|
| `id` | Xác định đúng sản phẩm trong đơn hàng |
| `name` | Hiển thị tên sản phẩm trong order detail |
| `price` | Tính tổng tiền đơn hàng |
| `available` | Quyết định có cho đặt hàng hay không |

> Contract **không** mô tả toàn bộ database model của Product. Chỉ mô tả những gì consumer thực sự sử dụng.

---

## Breaking vs Non-Breaking Changes

| Provider change | Breaking? | Lý do |
|----------------|-----------|-------|
| Thêm field `description` | ❌ No | Consumer bỏ qua field không cần, không ảnh hưởng |
| Đổi `name` → `productName` | ✅ Yes | Consumer đang đọc field `name`, đổi tên → parse lỗi |
| Đổi `price` từ `number` → `string` | ✅ Yes | Consumer expect `number` để tính toán, nhận `string` → lỗi type |
| Xóa field `available` | ✅ Yes | Consumer cần field này để check còn hàng, xóa → NullPointerException |
| Thêm optional field | ❌ No | Field mới không ảnh hưởng consumer hiện tại |

### Nguyên tắc chung

- **Non-breaking**: thêm field mới, thêm endpoint mới, thêm optional header.
- **Breaking**: xóa/đổi tên field đang dùng, đổi type, đổi status code, đổi URL path.

---

## Versioning & Backward Compatibility

- Mỗi contract gắn với **version** của consumer (ví dụ `order-service@1.2.0`).
- Provider cần verify tất cả contract của **mọi consumer version đang chạy trên production**.
- Khi cần breaking change → dùng API versioning (`/api/v2/products/{id}`) và duy trì version cũ cho đến khi consumer migrate xong.
