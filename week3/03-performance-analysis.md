# 03 - Performance Analysis

## Dữ liệu giả định

| **Load** | **p95 API latency** | **Error rate** | **App CPU** | **DB CPU** | **DB connections** | **Cache hit rate** |
| --- | --- | --- | --- | --- | --- | --- |
| 100 RPS | 120ms | 0% | 25% | 20% | 20/100 | 95% |
| 500 RPS | 280ms | 0.1% | 55% | 60% | 70/100 | 91% |
| 800 RPS | 1.8s | 3.5% | 65% | 95% | 100/100 | 88% |
| 1,000 RPS | 5.2s | 15% | 68% | 98% | 100/100 | 85% |

---

## Bảng Correlation

| Metric | 100→500 RPS | 500→800 RPS | 800→1000 RPS | Nhận xét |
| --- | --- | --- | --- | --- |
| p95 latency | 120ms → 280ms (+133%) | 280ms → 1.8s (+543%) | 1.8s → 5.2s (+189%) | Tăng đột biến từ 500→800 RPS |
| Error rate | 0% → 0.1% | 0.1% → 3.5% | 3.5% → 15% | Tăng mạnh khi DB saturated |
| App CPU | 25% → 55% | 55% → 65% | 65% → 68% | Tăng chậm, bão hoà sớm |
| DB CPU | 20% → 60% | 60% → 95% | 95% → 98% | Tăng rất nhanh, chạm ceiling |
| DB connections | 20 → 70 | 70 → 100 (max) | 100 (max) | Pool cạn kiệt từ 800 RPS |
| Cache hit rate | 95% → 91% | 91% → 88% | 88% → 85% | Giảm đều, tăng áp lực lên DB |

---

## 1. Bottleneck có khả năng cao nhất nằm ở đâu?

**Database** — cụ thể là **DB CPU saturation** kết hợp **connection pool exhaustion**.

- DB CPU đạt 95% ở 800 RPS và 98% ở 1000 RPS → database gần như hết khả năng xử lý.
- DB connection pool chạm max (100/100) từ 800 RPS → các request phải chờ connection, gây tăng latency và error.

---

## 2. Evidence nào hỗ trợ kết luận?

| Evidence | Giải thích |
| --- | --- |
| DB CPU 95–98% ở 800–1000 RPS | Database đang saturation, không còn headroom |
| DB connections = 100/100 (max pool) | Connection pool exhausted → request queue lên, latency tăng |
| p95 tăng đột biến 280ms → 1.8s khi DB CPU vượt 90% | Latency spike correlate trực tiếp với DB saturation |
| Error rate tăng từ 0.1% → 15% | Request timeout/fail do không lấy được DB connection hoặc DB query quá chậm |
| Cache hit rate giảm 95% → 85% | Nhiều cache miss hơn → nhiều query xuống DB hơn → tăng áp lực DB |

**Correlation rõ ràng:** Khi load tăng → cache hit giảm → query DB tăng → DB CPU saturate → connection pool cạn → latency và error rate tăng mạnh.

---

## 3. Vì sao Application CPU không phải root cause chính?

- App CPU chỉ đạt **65–68%** ở mức tải cao nhất → vẫn còn ~30% headroom, chưa saturation.
- App CPU **tăng chậm lại** từ 800→1000 RPS (65% → 68%, chỉ +3%) trong khi latency tăng rất mạnh (1.8s → 5.2s) → App CPU không phải yếu tố gây nghẽn.
- App CPU cao một phần vì **thread bị block** chờ DB connection/response, không phải do logic xử lý nặng.
- Nếu App CPU là root cause thì DB CPU sẽ không chạm 98% (vì app không đủ sức gửi nhiều query đến vậy).

**Kết luận:** App CPU là **symptom** (bị đẩy lên do thread chờ), không phải **root cause**.

---

## 4. Các bước điều tra tiếp theo

1. **Kiểm tra slow query log** — Tìm các query có execution time cao, đặc biệt ở 800+ RPS.
2. **Kiểm tra missing index** — Chạy `EXPLAIN` trên các query chính, tìm full table scan.
3. **Kiểm tra N+1 query** — Xem số query/request có tăng tuyến tính theo data size không.
4. **Kiểm tra DB lock contention** — Xem `innodb_row_lock_waits`, `lock_time` trong slow query log.
5. **Kiểm tra GC pause** trên application — Loại trừ memory pressure ảnh hưởng latency.
6. **Phân tích cache miss pattern** — Xác định tại sao cache hit rate giảm (TTL quá ngắn? key space lớn? cold cache?).
7. **Kiểm tra connection pool wait time** — Xem metric `hikari_connection_wait` hoặc tương đương.

---

## 5. Đề xuất Fix Plan

### Immediate Mitigation (ngay lập tức)
- **Tăng DB connection pool size** (100 → 150–200) nếu DB server cho phép — giảm connection wait.
- **Bật rate limiting** trên application — giới hạn ở ~500 RPS để giữ hệ thống ổn định.
- **Circuit breaker** cho các request khi DB quá tải — fail fast thay vì chờ timeout 5s.

### Short-term Fix (1–2 tuần)
- **Tối ưu slow query + thêm index** — giảm DB CPU per query.
- **Fix N+1 query** (nếu có) — giảm đáng kể số lượng query.
- **Tăng cache TTL / mở rộng cache coverage** — đẩy cache hit rate lên >95% ở mọi mức tải.
- **Read replica** cho read-heavy query — phân tải DB CPU.

### Long-term Architecture Improvement (1–3 tháng)
- **Database sharding hoặc partitioning** — scale horizontally cho DB.
- **CQRS pattern** — tách read/write path, read đi qua read replica + cache layer.
- **Async processing** — chuyển non-critical write sang message queue (Kafka/RabbitMQ).
- **Cache warming strategy** — preload cache khi deploy/restart, tránh cold start giảm hit rate.
- **Auto-scaling + load shedding** — tự động scale và bảo vệ hệ thống khi vượt capacity.

---

## Root Cause Hypothesis

> **Primary root cause:** Database là bottleneck chính — DB CPU saturation (98%) kết hợp connection pool exhaustion (100/100) gây ra cascade: latency tăng đột biến và error rate tăng mạnh.
>
> **Contributing factor:** Cache hit rate giảm dần theo load (95% → 85%), khiến nhiều request phải query trực tiếp DB, đẩy nhanh quá trình saturation.
>
> **Symptom (không phải root cause):** App CPU tăng (68%) và p95 latency cao (5.2s) là hệ quả của DB bottleneck, không phải nguyên nhân gốc.
