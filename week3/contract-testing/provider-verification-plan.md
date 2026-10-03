# Provider Verification Plan

## Mục tiêu

Product Service (provider) phải verify rằng implementation hiện tại đáp ứng contract mà Order Service (consumer) đã định nghĩa.

---

## Verification Flow

```
┌─────────────┐    publish     ┌──────────────┐    fetch      ┌─────────────────┐
│ Order Service│ ───────────▶  │  Pact Broker  │ ◀─────────── │ Product Service  │
│ (consumer)   │   contract    │  (lưu trữ)    │   contract   │ (provider)       │
└─────────────┘               └──────────────┘               └────────┬────────┘
                                                                       │
                                                              replay request
                                                              so sánh response
                                                                       │
                                                                 ✅ PASS / ❌ FAIL
```

## Các bước verify

### 1. Setup Provider States

Provider cần chuẩn bị data trước khi verify:

| State | Setup action |
|-------|-------------|
| `product P-100 exists` | Insert product P-100 vào test DB |
| `product P-999 does not exist` | Đảm bảo DB không có product P-999 |

### 2. Chạy Provider Verification

Provider verification đọc pact file, replay từng interaction:

| Interaction | Request | Expected Status | Verify Fields |
|-------------|---------|-----------------|---------------|
| Get existing product | `GET /api/products/P-100` | `200` | `id`, `name`, `price`, `available` có đúng type |
| Get non-existent product | `GET /api/products/P-999` | `404` | `status`, `error`, `message` |

### 3. Kiểm tra kết quả

- ✅ **PASS**: Tất cả field có mặt, đúng type, đúng status code.
- ❌ **FAIL**: Thiếu field, sai type, sai status code → block deployment.

---

## Tích hợp CI/CD

```
┌────────┐     ┌───────────┐     ┌─────────────────┐     ┌──────────┐
│  Push   │ ──▶│   Build   │ ──▶│ Provider Verify  │ ──▶│  Deploy  │
│  code   │    │  & Test   │    │ (pact verify)    │    │  (nếu ✅) │
└────────┘     └───────────┘     └─────────────────┘     └──────────┘
                                        │
                                   ❌ FAIL → Block deploy, notify team
```

### Nguyên tắc trong CI

1. **Provider verify phải chạy trong CI pipeline** — không chỉ chạy local.
2. **Block deployment** nếu verification fail — tránh deploy API break consumer.
3. **Verify tất cả consumer contracts** — nếu có nhiều consumer (Order, Inventory...), verify hết.
4. **Can-I-Deploy check** — dùng Pact Broker `can-i-deploy` để kiểm tra trước khi deploy version mới.

### Khi nào cần chạy verification

| Trigger | Hành động |
|---------|-----------|
| Provider push code mới | Verify tất cả contract hiện tại |
| Consumer publish contract mới | Trigger provider verification |
| Trước khi deploy lên production | Chạy `can-i-deploy` check |
