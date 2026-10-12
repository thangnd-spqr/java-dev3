# 07 — Performance Anti-patterns: `getDashboard`

## 1. Code gốc

```java
public List<EmployeeDashboardResponse> getDashboard(String tenantId) {
    List<Employee> employees = employeeRepository.findByTenantId(tenantId);   // (1)
    return employees.stream()
        .map(employee -> {
            Salary salary = salaryRepository.findLatestByEmployeeId(employee.getId()); // (2)
            TaxProfile tax = taxClient.getTaxProfile(employee.getId());               // (3)
            return new EmployeeDashboardResponse(employee, salary, tax);
        })
        .toList();
}
```

## 2. Nhận diện anti-pattern

| Dòng | Anti-pattern | Chi tiết |
| --- | --- | --- |
| (1) | **Fetch all / unbounded** | Lấy toàn bộ employee của tenant, không pagination |
| (2) | **N+1 query (DB)** | 1 query lấy list + **N** query lấy salary → N+1 round-trip DB |
| (3) | **N+1 remote call** | **N** HTTP call tới Tax Service, tuần tự, trong loop |
| (1)(2) | Missing index (có thể) | Cần index `employee(tenant_id)`, `salary(employee_id, effective_date DESC)` |
| — | Oversized payload | Trả nguyên entity / 10k phần tử trong 1 response |
| — | Transaction dài | Nếu có `@Transactional` bao cả method → giữ DB connection trong lúc chờ HTTP |

## 3. Rủi ro khi tenant có 10,000 employees

- DB: **10,001 query**; giả sử 2ms/query → ~20s chỉ cho DB, giữ connection suốt thời gian → **connection pool starvation**.
- Remote: **10,000 HTTP call** tuần tự; 100ms/call → **~1000s (~17 phút)** → chắc chắn timeout.
- Tax Service bị dội 10k request/1 dashboard → có thể sập, ảnh hưởng service khác (cascading failure).
- Memory: 10k entity + 10k salary + 10k tax + JSON lớn → heap/GC tăng, response vài MB.
- Thread bị giữ rất lâu → vài user mở dashboard cùng lúc là cạn thread pool (đúng triệu chứng bài 06).

## 4. Nếu chuyển sang WebFlux, đoạn nào vẫn blocking?

- `employeeRepository` / `salaryRepository` là **JPA/JDBC → blocking**, chạy trên event-loop sẽ treo cả server.
- `taxClient` nếu là `RestTemplate`/Feign → **blocking**.
- Chỉ đổi kiểu trả về `Mono/Flux` mà không đổi driver → **vẫn blocking**, thậm chí tệ hơn MVC.
- Phải: R2DBC (hoặc bọc `Schedulers.boundedElastic()` tạm thời) + `WebClient`. Và **WebFlux không sửa được N+1** — vẫn 10k query/call.

## 5. Refactor / optimization strategy

> **Đo trước khi tối ưu**: bật Hibernate statistics / `p6spy` đếm query, tracing đếm HTTP call, ghi baseline p95/p99 bằng load test.

1. **Pagination bắt buộc**: `Pageable` (max size 100), ưu tiên keyset pagination cho tenant lớn.
2. **Batch fetch DB** (diệt N+1): 1 query lấy salary mới nhất cho cả trang (`WHERE employee_id IN (:ids)`) hoặc fetch join/`@EntityGraph` / `@BatchSize`.
3. **Batch remote**: Tax Service cung cấp `getTaxProfiles(ids)`; nếu chưa có → gọi song song **có giới hạn concurrency**.
4. **Cache** tax profile (Caffeine/Redis, TTL vài giờ, invalidation qua event) — tránh cache cả list dashboard theo tenant (dễ stale, tốn memory).
5. **Resilience**: timeout, circuit breaker, fallback `TaxProfile.unavailable()`.
6. **Không** để `@Transactional` bao HTTP call; DTO projection thay vì entity đầy đủ.

```java
public Page<EmployeeDashboardResponse> getDashboard(String tenantId, Pageable page) {
    Page<EmployeeView> employees = employeeRepository.findByTenantId(tenantId, page); // 1 query, có index
    List<Long> ids = employees.map(EmployeeView::id).toList();

    Map<Long, Salary> salaries = salaryRepository.findLatestByEmployeeIds(ids)        // 1 query
        .stream().collect(toMap(Salary::employeeId, s -> s));
    Map<Long, TaxProfile> taxes = taxService.getTaxProfiles(ids);                      // cache + batch

    return employees.map(e -> new EmployeeDashboardResponse(
        e, salaries.get(e.id()), taxes.getOrDefault(e.id(), TaxProfile.unavailable())));
}

// TaxService: cache trước, phần miss gọi batch API (timeout + circuit breaker)
Map<Long, TaxProfile> getTaxProfiles(List<Long> ids) {
    Map<Long, TaxProfile> hit = cache.getAll(ids);
    List<Long> miss = ids.stream().filter(id -> !hit.containsKey(id)).toList();
    if (!miss.isEmpty()) hit.putAll(taxClient.batchGet(miss)); // fallback nếu chưa có batch:
    return hit;                                                // Flux.fromIterable(miss).flatMap(taxClient::get, 16)
}
```

Kết quả: 10,001 query → **2 query/trang**; 10,000 HTTP call → **≤1 batch call/trang** (hoặc 0 khi cache hit).

## 6. Metric cần theo dõi sau fix

| Nhóm | Metric |
| --- | --- |
| API | `http.server.requests` p50/p95/p99, error rate, payload size |
| DB | Số query/request (Hibernate statistics), slow query log, `hikaricp.connections.active/pending` |
| Remote | `http.client.requests` (Tax) count/request, latency, error, circuit breaker state |
| Cache | Hit ratio, eviction, size |
| JVM | `jvm.threads.states`, `tomcat.threads.busy`, allocation rate, GC pause |

**Test lại**: chạy cùng kịch bản load test (k6/Gatling, tenant 10k employees) như baseline, so sánh p99, số query, số remote call; thêm test assert số query (vd. `datasource-proxy` / Hypersistence `SQLStatementCountValidator`) để chống N+1 tái xuất hiện.

## 7. Checklist

- [x] Nhận diện N+1 DB (2) và N+1 remote call (3).
- [x] Đo baseline trước khi tối ưu, không tối ưu mù quáng.
- [x] Strategy: pagination + batch fetch + cache + concurrency limit.
- [x] Test lại performance và theo dõi metric sau fix.

