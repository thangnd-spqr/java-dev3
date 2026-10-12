# 05 — WebFlux Design: Chọn MVC hay WebFlux theo workload

## 1. Tóm tắt quyết định

| API | Chọn | Lý do chính |
| --- | --- | --- |
| A. `GET /employees/{id}` | **Spring MVC** | Nguồn dữ liệu là JPA/JDBC (blocking), có cache, traffic vừa |
| B. `GET /payroll-dashboard` | **WebFlux + WebClient** (hoặc MVC + virtual threads) | Fan-out 3 remote call chậm, gọi song song, I/O-bound |

> Không chọn vì "WebFlux nhanh hơn". WebFlux không làm 1 request nhanh hơn; nó giúp **dùng ít thread** khi phải **chờ nhiều I/O chậm**, nên scale dễ đoán hơn.

## 2. API A — `GET /employees/{id}` → MVC

- **Blocking source**: `EmployeeRepository` (JPA/Hibernate → JDBC driver → socket chờ DB). Hibernate session, lazy loading, `@Transactional` đều gắn với **ThreadLocal**.
- Cache hit (Caffeine/Redis) → trả về vài ms, DB chỉ bị gọi khi miss → thread bị block rất ngắn.
- Traffic vừa → thread pool Tomcat (200 threads) dư sức.
- Nếu ép WebFlux mà vẫn JPA: gọi `repo.findById()` trên **event-loop** (chỉ ~số core thread) → 1 query chậm làm **treo toàn bộ** request khác. Bọc `Schedulers.boundedElastic()` chỉ là quay lại thread-pool, thêm phức tạp mà không lợi gì.
- Muốn WebFlux thật sự → phải đổi sang **R2DBC** (mất lazy loading, JPA mapping, đổi cả team) → không đáng cho CRUD nội bộ.

```java
@GetMapping("/employees/{id}")
@Cacheable(value = "employee", key = "#id")
public EmployeeDto get(@PathVariable Long id) {
    return service.findById(id);           // blocking, OK trong MVC
}
```

Timeout cho A: `spring.datasource.hikari.connection-timeout=2s`, JPA query timeout `javax.persistence.query.timeout=3000`, cache TTL + fallback 404/503 rõ ràng.

## 3. API B — `GET /payroll-dashboard` → WebFlux

- **Blocking source** (nếu làm kiểu MVC truyền thống): 3 lần gọi HTTP tuần tự `RestTemplate` → 200–800ms × 3 = **tới 2.4s**, thread bị giữ suốt thời gian chờ.
- Gọi song song bằng `Mono.zip` → latency ≈ **max(3 call) ≈ 800ms**, và trong lúc chờ **không giữ thread nào**.

### Dependency bắt buộc non-blocking

| Thành phần | Phải dùng | Không được dùng |
| --- | --- | --- |
| HTTP client | `WebClient` (Reactor Netty) | `RestTemplate`, Feign blocking |
| DB (nếu có) | R2DBC, Reactive Redis/Mongo | JPA, JDBC, `JdbcTemplate` |
| Security/context | `ReactiveSecurityContextHolder`, Reactor Context | `ThreadLocal`, MDC thuần |
| Logging/IO khác | Async appender | File I/O / `Thread.sleep` trên event-loop |

> Phát hiện blocking call: dùng **BlockHound** trong test.

### Timeout / Fallback / Concurrency limit

```java
public Mono<Dashboard> dashboard(String empId) {
    Mono<Payroll>  payroll  = payrollClient.get(empId)
        .timeout(Duration.ofSeconds(1))
        .retryWhen(Retry.backoff(1, Duration.ofMillis(100)).filter(this::isTransient));
    Mono<Tax>      tax      = taxClient.get(empId)
        .timeout(Duration.ofSeconds(1))
        .onErrorReturn(Tax.unavailable());                 // fallback: degrade
    Mono<Benefits> benefits = benefitsClient.get(empId)
        .timeout(Duration.ofSeconds(1))
        .onErrorResume(e -> cache.lastKnown(empId));       // fallback: stale cache

    return Mono.zip(payroll, tax, benefits)
        .map(t -> new Dashboard(t.getT1(), t.getT2(), t.getT3()))
        .timeout(Duration.ofMillis(1500));                 // tổng deadline
}
```

| Cơ chế | Cấu hình đề xuất |
| --- | --- |
| **Timeout** | Connect 300ms, response 1s/call (> p99 800ms), tổng 1.5s |
| **Retry** | Tối đa 1 lần, chỉ lỗi transient (5xx/timeout), backoff + jitter, request phải idempotent (GET) |
| **Fallback** | Payroll là **bắt buộc** → lỗi thì trả 503; Tax/Benefits → trả partial + flag `"unavailable"` hoặc cache cũ |
| **Circuit breaker** | Resilience4j per downstream: mở khi lỗi > 50% → fail fast, không dồn request vào service đang chết |
| **Concurrency limit** | Bulkhead/ connection pool `WebClient` (maxConnections ~100/service, pendingAcquireMaxCount), `flatMap(..., n)` khi fan-out list; rate limit theo tenant |

### Phương án thay thế

Nếu team chưa quen reactive debugging: **MVC + Java 21 virtual threads** + `CompletableFuture`/structured concurrency cũng gọi song song được, code imperative dễ debug. Vẫn cần timeout/circuit breaker như trên.

## 4. Checklist

- [x] Chọn stack theo workload (A: blocking CRUD → MVC; B: fan-out I/O → WebFlux).
- [x] Nhận diện blocking call: JPA/JDBC, `RestTemplate`, `ThreadLocal`.
- [x] WebFlux + JPA = block event-loop → rủi ro treo toàn service.
- [x] Mỗi downstream có timeout, retry giới hạn, fallback, circuit breaker, bulkhead.

