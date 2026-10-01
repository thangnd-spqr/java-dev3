# 02 - Load Testing Tools & Scripting

---

## 1. So sánh Tool

| **Tool** | **Script** | **Phù hợp khi** |
|---|---|---|
| **k6** | JavaScript | CI-friendly, developer-centric, dễ version control |
| **Gatling** | Scala DSL | Hệ Java/Scala, report HTML đẹp |
| **JMeter** | GUI/XML | Cần GUI, quick setup, không cần code |

---

## 2. Các khái niệm trong Script

| **Khái niệm** | **Ý nghĩa** |
|---|---|
| **Virtual Users (VUs)** | Số user ảo chạy song song. 50 VU = 50 user đồng thời |
| **Stages** | Ramp-up → Hold → Ramp-down, mô phỏng traffic thực tế |
| **Checks/Assertions** | Kiểm tra response: status code, body |
| **Thresholds** | Điều kiện pass/fail tự động: p95, error rate |
| **Test Data** | Dữ liệu đầu vào hợp lệ, tránh fail do validation |

---

## 3. Assignment — k6 Script cho Order API

### Yêu cầu

```
Endpoints:   POST /api/orders | GET /api/orders/{id} | GET /api/products?page=1&pageSize=20
User journey: Xem products → Xem 1 product → Tạo order → Kiểm tra order
Tải:         0→50 VUs (1m) → giữ 50 VUs (3m) → 0 (30s)
Thresholds:  p95 GET products < 300ms | p95 POST order < 800ms | Error < 1%
```

### Script: `order-flow-test.js`

```javascript
import http from "k6/http";
import { check, sleep, group } from "k6";

const BASE_URL = "https://test.example.com";
const CUSTOMERS = ["customer-001","customer-002","customer-003","customer-004","customer-005"];

export const options = {
  stages: [
    { duration: "1m", target: 50 },
    { duration: "3m", target: 50 },
    { duration: "30s", target: 0 },
  ],
  thresholds: {
    http_req_failed: ["rate<0.01"],
    "http_req_duration{endpoint:product-list}": ["p(95)<300"],
    "http_req_duration{endpoint:create-order}": ["p(95)<800"],
  },
};

export default function () {
  // Step 1: Xem product list
  const listRes = http.get(`${BASE_URL}/api/products?page=1&pageSize=20`,
    { tags: { endpoint: "product-list" } });
  check(listRes, { "product list 200": (r) => r.status === 200 });
  sleep(1);

  // Step 2: Xem 1 product
  const detailRes = http.get(`${BASE_URL}/api/products?page=1&pageSize=1`,
    { tags: { endpoint: "product-detail" } });
  check(detailRes, { "product detail 200": (r) => r.status === 200 });
  sleep(1);

  // Step 3: Tạo order
  const orderRes = http.post(`${BASE_URL}/api/orders`,
    JSON.stringify({
      customerId: CUSTOMERS[Math.floor(Math.random() * CUSTOMERS.length)],
      items: [{ productId: "product-1", quantity: Math.floor(Math.random() * 5) + 1 }],
    }),
    { headers: { "Content-Type": "application/json" }, tags: { endpoint: "create-order" } }
  );
  check(orderRes, { "create order 2xx": (r) => r.status === 200 || r.status === 201 });
  sleep(1);

  // Step 4: Kiểm tra order vừa tạo
  try {
    const orderId = JSON.parse(orderRes.body).id;
    if (orderId) {
      const checkRes = http.get(`${BASE_URL}/api/orders/${orderId}`,
        { tags: { endpoint: "check-order" } });
      check(checkRes, { "check order 200": (r) => r.status === 200 });
    }
  } catch (e) {}
  sleep(1);
}
```

### Chạy test

```bash
k6 run order-flow-test.js
k6 run --out json=results.json order-flow-test.js
```

### Hạn chế

- Chưa có authentication (cần thêm login step nếu API yêu cầu).
- Test data hardcode, cần đảm bảo tồn tại trong DB staging.
- Think time cố định (`sleep(1)`), nên dùng `sleep(Math.random() * 3 + 1)` cho realistic hơn.

---

## 4. Checklist

- [x] Script có ramp-up / ramp-down.
- [x] Có check/assertion response.
- [x] Có threshold p95 / error rate.
- [x] Test data không làm request fail do dữ liệu giả sai.
- [x] Script chạy lặp lại được, nằm trong source control.
