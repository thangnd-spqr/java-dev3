# Product Contract

## Consumer → Provider

- **Consumer**: Order Service
- **Provider**: Product Service

---

## Interaction: Lấy thông tin sản phẩm

### Request

| Thuộc tính | Giá trị |
|------------|---------|
| Method | `GET` |
| Path | `/api/products/{productId}` |
| Headers | `Accept: application/json` |

### Success Response (200 OK)

```json
{
  "id": "P-100",
  "name": "Mechanical Keyboard",
  "price": 120.50,
  "available": true
}
```

### Required Fields & Types

| Field | Type | Required | Mô tả |
|-------|------|----------|--------|
| `id` | `string` | ✅ | Product ID, format `P-{number}` |
| `name` | `string` | ✅ | Tên sản phẩm |
| `price` | `number` | ✅ | Giá sản phẩm (> 0) |
| `available` | `boolean` | ✅ | Còn hàng hay không |

> **Lưu ý**: Contract chỉ mô tả những field mà consumer (Order Service) thực sự cần. Provider có thể trả thêm field khác (ví dụ `description`, `category`) — consumer sẽ bỏ qua chúng.

---

## Error Response: Product không tồn tại (404 Not Found)

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Product not found"
}
```

| Field | Type | Required | Mô tả |
|-------|------|----------|--------|
| `status` | `number` | ✅ | HTTP status code |
| `error` | `string` | ✅ | Loại lỗi |
| `message` | `string` | ✅ | Mô tả lỗi |

---

## Provider States

| State | Mô tả |
|-------|--------|
| `product P-100 exists` | Product P-100 tồn tại trong DB → trả 200 |
| `product P-999 does not exist` | Product không tồn tại → trả 404 |
