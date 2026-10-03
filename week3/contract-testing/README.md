# Consumer-Driven Contract Testing

## Tổng quan

**Consumer-Driven Contract (CDC)** là phương pháp kiểm thử tích hợp, trong đó **consumer** (service gọi API) định nghĩa kỳ vọng về API mà **provider** (service cung cấp API) phải đáp ứng. Contract này trở thành "hợp đồng" giữa hai bên.

## Flow hoạt động

```
1. Order Service (consumer) viết contract:
   "Khi gọi GET /products/P-1, tôi cần status 200 và field id, name, price, available."

2. Contract được publish lên Pact Broker (hoặc Git repo).

3. Product Service (provider) chạy provider verification:
   "Implementation hiện tại có đáp ứng contract của Order Service không?"

4. Nếu provider đổi API làm contract fail → CI/CD block deployment hoặc cảnh báo team.
```

## Pact là gì

Pact là framework phổ biến nhất cho CDC testing. Ở mức khái niệm:

- **Consumer test** → tạo ra file **pact** (JSON mô tả interaction).
- **Provider verification** → đọc pact file, replay request lên provider thật, so sánh response.
- **Pact Broker** → nơi lưu trữ và quản lý version của contract giữa các service.

## Cấu trúc thư mục

| File | Mô tả |
|------|--------|
| `product-contract.md` | Contract chi tiết: request, response, error, field types |
| `consumer-expectations.md` | Kỳ vọng của consumer, breaking/non-breaking change |
| `provider-verification-plan.md` | Kế hoạch verify contract phía provider, tích hợp CI |
