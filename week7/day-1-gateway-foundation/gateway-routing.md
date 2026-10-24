# Gateway Routing – API Gateway với Spring Cloud Gateway

## 1. Vai trò của API Gateway

API Gateway là **cửa ngõ duy nhất (single entry point)** cho mọi request từ bên ngoài vào hệ thống microservices.

```text
Client (Web / Mobile / Partner)
        │
        ▼
   API Gateway  ── CORS, Auth token forwarding, Correlation ID, Logging, Rate limit
        │
        ├── /api/orders/**     → order-service
        ├── /api/inventory/**  → inventory-service
        └── /api/payments/**   → payment-service
```

![Gateway diagram](gateway-diagram.png)

### Gateway NÊN xử lý (cross-cutting concerns)

| Concern | Mô tả |
|---|---|
| Routing | Map path/host/header → service đích |
| CORS | Cấu hình tập trung origin, method, header được phép |
| Authentication token forwarding | Validate JWT (chữ ký, hạn) ở mức cơ bản, forward `Authorization` xuống service |
| Request logging | Log method, path, status, latency |
| Correlation ID | Gắn `X-Correlation-Id` để trace xuyên suốt các service |
| Rate limiting | Giới hạn request theo user/IP/API key (`RequestRateLimiter` + Redis) |
| Request/response policy | Strip prefix, thêm/bỏ header, timeout, retry, circuit breaker, giới hạn body size |

### Gateway KHÔNG NÊN chứa business logic

- ❌ Tính giá đơn hàng → thuộc **order-service / pricing**.
- ❌ Kiểm tra tồn kho → thuộc **inventory-service**.
- ❌ Charge payment → thuộc **payment-service**.
- ❌ Quyết định trạng thái order → thuộc **order-service** (domain logic).

> Nếu đưa business logic vào gateway, gateway sẽ trở thành một "monolith mới", mọi thay đổi nghiệp vụ đều phải deploy gateway → coupling cao, single point of failure về mặt phát triển.

---

## 2. Cấu trúc project thực hành

```text
order-platform/
├── api-gateway/          # Spring Cloud Gateway (port 8080)
├── order-service/        # port 8081
├── inventory-service/    # port 8082
├── payment-service/      # port 8083
└── docker-compose.yml
```

### Dependency của `api-gateway` (Gradle)

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.3.5'
    id 'io.spring.dependency-management' version '1.1.6'
}

ext { set('springCloudVersion', '2023.0.3') }

dependencies {
    implementation 'org.springframework.cloud:spring-cloud-starter-gateway'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    // tuỳ chọn:
    // implementation 'org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j'
    // implementation 'org.springframework.boot:spring-boot-starter-data-redis-reactive' // cho rate limiter
}

dependencyManagement {
    imports { mavenBom "org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}" }
}
```

---

## 3. Route configuration (không hard-code URL)

URL của service được đọc từ **biến môi trường**, có giá trị mặc định cho local. Business code không biết URL cụ thể.

`api-gateway/src/main/resources/application.yml`

```yaml
server:
  port: 8080

spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      httpclient:
        connect-timeout: ${GATEWAY_CONNECT_TIMEOUT_MS:2000}
        response-timeout: ${GATEWAY_RESPONSE_TIMEOUT:5s}
      globalcors:
        cors-configurations:
          '[/**]':
            allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000}
            allowed-methods: [GET, POST, PUT, DELETE, OPTIONS]
            allowed-headers: ["*"]
            exposed-headers: [X-Correlation-Id]
      default-filters:
        - DedupeResponseHeader=Access-Control-Allow-Origin
      routes:
        - id: order-service
          uri: ${ORDER_SERVICE_URL:http://localhost:8081}
          predicates:
            - Path=/api/orders/**

        - id: inventory-service
          uri: ${INVENTORY_SERVICE_URL:http://localhost:8082}
          predicates:
            - Path=/api/inventory/**

        - id: payment-service
          uri: ${PAYMENT_SERVICE_URL:http://localhost:8083}
          predicates:
            - Path=/api/payments/**

management:
  endpoints:
    web:
      exposure:
        include: health,info,gateway
```

> Nếu dùng **Eureka/Consul**, `uri` đổi thành `lb://order-service` — Spring Cloud LoadBalancer sẽ chọn instance (round-robin mặc định).
> Nếu chạy **Kubernetes**, `uri` là `http://order-service:8081` (K8s Service DNS) — kube-proxy tự load balance giữa các pod.

### Bảng routing

| Path | Route ID | Service đích (local) | Docker / K8s |
|---|---|---|---|
| `/api/orders/**` | order-service | `http://localhost:8081` | `http://order-service:8081` |
| `/api/inventory/**` | inventory-service | `http://localhost:8082` | `http://inventory-service:8082` |
| `/api/payments/**` | payment-service | `http://localhost:8083` | `http://payment-service:8083` |

---

## 4. Global filter: Correlation ID + Request logging

Yêu cầu:
1. Đọc header `X-Correlation-Id`.
2. Nếu chưa có → generate ID mới (UUID).
3. Forward ID xuống service phía sau (và trả về trong response).
4. Log method, path, status, correlation ID (kèm latency).

`api-gateway/src/main/java/com/example/gateway/filter/CorrelationIdGlobalFilter.java`

```java
package com.example.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();

        // 1. Đọc header, 2. nếu chưa có thì generate
        String incoming = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
        String correlationId = StringUtils.hasText(incoming) ? incoming : UUID.randomUUID().toString();

        // 3. Forward xuống downstream service
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(h -> h.set(CORRELATION_ID_HEADER, correlationId))
                .build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

        // Trả lại cho client để client/support dễ tra log
        mutatedExchange.getResponse().beforeCommit(() -> {
            mutatedExchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);
            return Mono.empty();
        });

        // 4. Log method, path, status, correlationId
        return chain.filter(mutatedExchange)
                .doFinally(signal -> {
                    HttpStatusCode status = mutatedExchange.getResponse().getStatusCode();
                    log.info("method={} path={} status={} correlationId={} durationMs={}",
                            mutatedRequest.getMethod(),
                            mutatedRequest.getURI().getRawPath(),
                            status != null ? status.value() : "N/A",
                            correlationId,
                            System.currentTimeMillis() - start);
                });
    }

    @Override
    public int getOrder() {
        // Chạy sớm nhất để mọi filter sau đều có correlation ID
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
```

### Phía downstream service: đưa Correlation ID vào MDC

Mỗi service (order/inventory/payment) đọc header và đưa vào MDC để mọi dòng log đều có `correlationId`.

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = Optional.ofNullable(req.getHeader(HEADER))
                .filter(s -> !s.isBlank())
                .orElse(UUID.randomUUID().toString());
        MDC.put(MDC_KEY, id);
        res.setHeader(HEADER, id);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
```

`application.yml` của service:

```yaml
logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{correlationId:-}]"
```

> Khi service A gọi service B (RestClient/WebClient/Feign), cần **propagate tiếp** header `X-Correlation-Id` lấy từ MDC (dùng interceptor). Trong production nên dùng **Micrometer Tracing + OpenTelemetry** (`traceparent`) — correlation ID tự viết vẫn hữu ích cho log nghiệp vụ và support.

---

## 5. Gọi service-to-service không hard-code URL

```yaml
# order-service/application.yml
clients:
  inventory:
    base-url: ${INVENTORY_SERVICE_URL:http://localhost:8082}
    timeout: ${INVENTORY_TIMEOUT:2s}
```

```java
@ConfigurationProperties(prefix = "clients.inventory")
public record InventoryClientProperties(String baseUrl, Duration timeout) {}

@Configuration
@EnableConfigurationProperties(InventoryClientProperties.class)
class InventoryClientConfig {
    @Bean
    RestClient inventoryRestClient(RestClient.Builder builder, InventoryClientProperties props) {
        return builder
                .baseUrl(props.baseUrl())
                .requestInterceptor((request, body, execution) -> {
                    String cid = MDC.get("correlationId");
                    if (cid != null) request.getHeaders().set("X-Correlation-Id", cid);
                    return execution.execute(request, body);
                })
                .build();
    }
}
```

Business code chỉ dùng `inventoryRestClient.get().uri("/api/inventory/{sku}", sku)` — **không có host/port** nào trong code.

---

## 6. docker-compose.yml

```yaml
services:
  api-gateway:
    build: ./api-gateway
    ports: ["8080:8080"]
    environment:
      ORDER_SERVICE_URL: http://order-service:8081
      INVENTORY_SERVICE_URL: http://inventory-service:8082
      PAYMENT_SERVICE_URL: http://payment-service:8083
      CORS_ALLOWED_ORIGINS: http://localhost:3000
    depends_on: [order-service, inventory-service, payment-service]

  order-service:
    build: ./order-service
    environment:
      SERVER_PORT: 8081
      INVENTORY_SERVICE_URL: http://inventory-service:8082
      PAYMENT_SERVICE_URL: http://payment-service:8083
      DB_PASSWORD: ${ORDER_DB_PASSWORD}   # lấy từ file .env (không commit)

  inventory-service:
    build: ./inventory-service
    environment:
      SERVER_PORT: 8082

  payment-service:
    build: ./payment-service
    environment:
      SERVER_PORT: 8083
      PAYMENT_PROVIDER_API_KEY: ${PAYMENT_PROVIDER_API_KEY}  # secret từ .env
```

> Trong Docker network, tên service (`order-service`) chính là DNS name → không cần hard-code IP.
> Chỉ `api-gateway` expose port ra ngoài; các service khác chỉ truy cập được trong network nội bộ.

---

## 7. Kiểm thử

```bash
# Không gửi correlation ID → gateway tự sinh
curl -i http://localhost:8080/api/orders/1
# HTTP/1.1 200 OK
# X-Correlation-Id: 3f6c1d9a-...

# Gửi sẵn correlation ID → gateway giữ nguyên và forward
curl -i -H "X-Correlation-Id: test-123" http://localhost:8080/api/inventory/SKU-1
curl -i -H "X-Correlation-Id: test-456" http://localhost:8080/api/payments/9

# Xem các route đã đăng ký
curl http://localhost:8080/actuator/gateway/routes
```

Log mong đợi:

```text
# api-gateway
INFO  CorrelationIdGlobalFilter : method=GET path=/api/inventory/SKU-1 status=200 correlationId=test-123 durationMs=18
# inventory-service
INFO [inventory-service,test-123] InventoryController : Get stock for SKU-1
```

---

## 8. Gateway vs Load Balancer

| Tiêu chí | Load Balancer (L4/L7: Nginx, ALB, kube-proxy) | API Gateway (Spring Cloud Gateway, Kong) |
|---|---|---|
| Mục đích chính | Phân phối traffic giữa **nhiều instance của cùng 1 service** | Điểm vào duy nhất, route tới **nhiều service khác nhau** |
| Hiểu ngữ cảnh API | Ít (L4 chỉ IP/port; L7 hiểu path/host) | Hiểu sâu: path, header, JWT, API version |
| Cross-cutting | Health check, TLS termination | Auth, rate limit, CORS, correlation ID, transform, circuit breaker |
| Vị trí | Thường đứng trước gateway và/hoặc trước mỗi service | Đứng sau LB biên, trước các service |

Trong thực tế thường kết hợp: `Client → Cloud LB → API Gateway (nhiều instance) → Service (LB nội bộ / K8s Service) → Pods`.

