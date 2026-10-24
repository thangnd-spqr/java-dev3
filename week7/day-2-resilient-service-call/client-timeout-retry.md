# HTTP Client, Timeout & Retry – Gọi service khác an toàn

## 0. Phân tích sự cố

```text
POST /orders ──► Order Service ──► Inventory Service (chậm 5s)
                     │
                     ├─ timeout 30s           → thread bị giữ tới 5s (hoặc 30s nếu treo hẳn)
                     ├─ retry 3 lần ngay lập tức → 1 request user = 4 request xuống Inventory (retry storm)
                     └─ dùng chung Tomcat thread pool + Hikari pool
                                 → Get Order (không liên quan Inventory) cũng phải xếp hàng
```

Dùng **Little's Law** để thấy vì sao thread pool cạn:

$$
\text{Concurrency} = \text{Throughput} \times \text{Latency}
$$

| | Throughput checkout | Latency mỗi checkout | Thread bị giữ |
|---|---|---|---|
| Bình thường | 40 req/s | 0.05 s | 2 |
| Inventory chậm 5s, không retry | 40 req/s | 5 s | **200** (= hết Tomcat pool mặc định) |
| Inventory chậm 5s, retry 3 lần ngay | 40 req/s | 20 s | **800** → queue, timeout dây chuyền |

Khi 200 thread Tomcat đều đang chờ Inventory, request `GET /orders/{id}` (chỉ đọc DB, 10ms) cũng không có thread để chạy → **cascading failure**. Retry ngay lập tức còn nhân 4 tải lên Inventory đang yếu → nó càng khó hồi phục.

Bốn cơ chế giải quyết 4 phần khác nhau của sự cố:

| Cơ chế | Trả lời câu hỏi | Giải quyết phần nào |
|---|---|---|
| **Timeout** | Chờ *một lần gọi* tối đa bao lâu? | Thread không bị giữ 30s |
| **Retry** (backoff + jitter) | Lỗi *tạm thời* thì thử lại thế nào? | Vượt qua lỗi thoáng qua mà không gây retry storm |
| **Circuit Breaker** | Dependency đang hỏng thì có nên gọi tiếp không? | Fail fast, cho Inventory thời gian hồi phục |
| **Bulkhead** | Tối đa bao nhiêu tài nguyên được dành cho dependency này? | Inventory chậm không chiếm hết thread của Get Order/Payment |

---

## 1. OpenFeign vs WebClient vs RestClient

| Công cụ | Kiểu | Phù hợp khi | Lưu ý |
|---|---|---|---|
| **OpenFeign** (Spring Cloud) | Declarative interface, blocking | Spring MVC, nhiều service-to-service call, muốn code gọn như interface; tích hợp sẵn Spring Cloud LoadBalancer (`lb://`), CircuitBreaker | Thêm dependency Spring Cloud; Spring team khuyến nghị dự án mới cân nhắc RestClient + HTTP Interface; Feign đang ở chế độ "feature complete" |
| **WebClient** (WebFlux) | Non-blocking, reactive (`Mono`/`Flux`) | App WebFlux; cần gọi song song nhiều service (fan-out/aggregation); streaming; Gateway | Trong MVC thì phải `.block()` → mất lợi ích; debug/stacktrace khó hơn |
| **RestClient** (Spring 6.1+) | Fluent API, blocking | Spring MVC hiện đại, gọi HTTP đơn giản; thay thế RestTemplate | Kết hợp `@HttpExchange` (HTTP Interface) để có interface khai báo như Feign mà không cần Spring Cloud |

Ví dụ cùng một call với 3 cách:

```java
// OpenFeign
@FeignClient(name = "inventory-service", url = "${clients.inventory.base-url}")
interface InventoryClient {
    @PostMapping("/api/inventory/reservations")
    ReservationResponse reserve(@RequestHeader("Idempotency-Key") String key,
                                @RequestBody ReserveRequest req);
}

// RestClient
ReservationResponse res = inventoryRestClient.post()
        .uri("/api/inventory/reservations")
        .header("Idempotency-Key", key)
        .body(req)
        .retrieve()
        .body(ReservationResponse.class);

// WebClient (reactive)
Mono<ReservationResponse> res = inventoryWebClient.post()
        .uri("/api/inventory/reservations")
        .header("Idempotency-Key", key)
        .bodyValue(req)
        .retrieve()
        .bodyToMono(ReservationResponse.class)
        .timeout(Duration.ofSeconds(1));
```

> Bài thực hành dùng **OpenFeign + Resilience4j annotations** vì Order Service là Spring MVC (blocking). Nếu dự án mới, `RestClient` + `@HttpExchange` cho kết quả tương tự.

---

## 2. Timeout

### 2.1. Ba loại timeout

| Loại | Ý nghĩa | Khi nào xảy ra | Giá trị gợi ý (nội bộ cluster) |
|---|---|---|---|
| **Connect timeout** | Thời gian thiết lập TCP (+TLS) connection | Host chết, sai IP, firewall drop SYN | 200–500 ms |
| **Read / response timeout** | Thời gian chờ response sau khi đã gửi request (socket idle giữa 2 gói) | Service chậm, GC pause, DB lock | Theo p99 của dependency × 1.5–2 |
| **Overall request timeout (deadline)** | Tổng thời gian cho cả flow, **bao gồm mọi lần retry + backoff** | Mọi thứ cộng dồn | < timeout của caller phía trên |

```text
connect      ├──┤
read                ├──────────────┤
attempt 1    ├──────────────────────┤ backoff ├─ attempt 2 ─┤ backoff ├─ attempt 3 ─┤
overall      ├──────────────────────────────────────────────────────────────────────┤
```

> ⚠️ Read timeout của Feign/Apache/JDK HttpClient là **thời gian chờ giữa các byte**, không phải tổng thời gian. Server gửi nhỏ giọt từng byte vẫn có thể giữ connection lâu hơn. Cần overall deadline (TimeLimiter / `CompletableFuture.orTimeout` / `Mono.timeout`) nếu muốn chặn cứng.

### 2.2. Timeout budget phải giảm dần theo chiều sâu

```text
Client (browser)       10s
  └─ API Gateway        5s   (response-timeout của day-1)
       └─ Order Service checkout overall ≤ 4s
            └─ Inventory call: 1s/attempt × 3 attempts + backoff ≈ 3.6s  ✅ < 4s
```

Quy tắc:
1. **Timeout không quá dài** – 30s nghĩa là chấp nhận giữ thread 30s; không user nào chờ 30s.
2. **Timeout con < timeout cha** – nếu Order chờ Inventory 30s trong khi Gateway chỉ chờ 5s, Gateway đã trả 504 cho user nhưng Order vẫn tiếp tục làm (và retry!) → lãng phí, có thể tạo side effect "ma".
3. **Theo SLO** – SLO checkout p99 < 2s ⇒ một dependency không thể có timeout 5s. Đặt read timeout ≈ p99 bình thường của Inventory (ví dụ p99 = 300ms → timeout 1s).
4. Tính cả retry: `overall ≈ attempts × timeout + Σ backoff`.

### 2.3. Cấu hình timeout cho từng client

```yaml
# OpenFeign (Spring Cloud 2023.x)
spring:
  cloud:
    openfeign:
      client:
        config:
          inventory-service:
            connect-timeout: 500    # ms
            read-timeout: 1000      # ms  ← yêu cầu "Timeout 1 giây"
```

```java
// RestClient (JDK HttpClient)
var factory = new JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build());
factory.setReadTimeout(Duration.ofSeconds(1));
RestClient client = RestClient.builder().requestFactory(factory).build();

// WebClient (Reactor Netty)
HttpClient http = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
        .responseTimeout(Duration.ofSeconds(1));
WebClient client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
```

---

## 3. Retry

### 3.1. Lỗi nào được retry, lỗi nào không

| Lỗi | Retry? | Lý do |
|---|---|---|
| `ConnectException`, connection refused/reset | ✅ | Request chưa tới server hoặc instance vừa chết; instance khác có thể OK |
| `503 Service Unavailable`, `502`, `504` | ✅ | Tạm thời: overload, đang deploy, LB chưa sẵn sàng |
| `429 Too Many Requests` | ✅ có điều kiện | Tôn trọng header `Retry-After`, backoff dài hơn |
| Read timeout | ⚠️ **Chỉ khi idempotent** | Server **có thể đã xử lý xong** – retry thao tác không idempotent ⇒ trừ kho 2 lần / charge tiền 2 lần |
| `500 Internal Server Error` | ⚠️ Chỉ khi idempotent, số lần thấp | Có thể là bug (retry vô ích) hoặc lỗi tạm thời |
| `400` validation error | ❌ | Gửi lại y nguyên vẫn sai |
| `401` / `403` | ❌ | Lỗi auth/quyền, cần refresh token hoặc sửa quyền chứ không phải retry |
| `404`, `409`, `422` business rule (hết hàng, SKU không tồn tại) | ❌ | Quyết định nghiệp vụ, không phải lỗi hạ tầng |
| `CallNotPermittedException` (circuit open) | ❌ | Retry ngay khi circuit open là vô nghĩa và phá mục đích của CB |
| `BulkheadFullException` | ❌ | Đang quá tải; retry làm quá tải thêm |
| Payment request **không có idempotency key** | ❌ **Tuyệt đối** | Có thể charge khách hàng 2 lần |

### 3.2. Retry đúng cần 4 thứ

1. **Retry limit** – tối đa 2 lần retry (= 3 attempts). Retry nhiều tầng nhân lên theo cấp số: Gateway 3 × Order 3 × Inventory→DB 3 = 27 lần. ⇒ **Chỉ retry ở một tầng** (thường là tầng gần dependency nhất).
2. **Exponential backoff** – chờ 200ms → 400ms → 800ms… để dependency có thời gian hồi phục.
3. **Jitter** – random hoá thời gian chờ, tránh hàng nghìn client retry **cùng một thời điểm** (thundering herd).
4. **Idempotency** – retry chỉ an toàn khi gọi lại không tạo thêm side effect.

```text
wait(n) = base × multiplier^(n-1) ± random(randomizationFactor)

base=200ms, multiplier=2, randomizationFactor=0.5
  retry 1: 200ms ± 50%  → 100..300ms
  retry 2: 400ms ± 50%  → 200..600ms
```

```text
Không jitter:   ████ ████ ████        (mọi client retry cùng lúc → spike)
Có jitter:      █ ██ █ █ ███ █ ██ █   (tải dàn đều)
```

### 3.3. Idempotency cho từng loại call

| Call | Idempotent tự nhiên? | Chiến lược |
|---|---|---|
| `GET /inventory/{sku}` | ✅ | Retry thoải mái (vẫn có limit + backoff) |
| `POST /inventory/reservations` (reserve stock) | ❌ | Gửi `Idempotency-Key: order-{orderId}-reserve`; Inventory lưu key + kết quả, nhận lại key cũ thì trả kết quả cũ ⇒ retry an toàn, kể cả khi timeout |
| `POST /payments` (charge) | ❌ (rủi ro tiền) | Bắt buộc `Idempotency-Key: order-{orderId}-payment-{attemptNo}`; retry chỉ với **cùng key**; timeout ⇒ **không retry mù**, mà query trạng thái theo key rồi reconcile (chi tiết [resilience-implementation.md §6](resilience-implementation.md#6-payment-không-retry-mù-quáng)) |

