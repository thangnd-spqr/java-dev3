# 06 — Profiling Runbook: Payroll Dashboard p99 = 8s

## 1. Scenario & phân loại

| Tín hiệu | Giá trị | Ý nghĩa |
| --- | --- | --- |
| p99 latency | 8s | Rất chậm |
| CPU app | 35% | **Không** CPU-bound |
| Heap | ổn định | **Không** memory-bound (không leak, GC không phải thủ phạm) |
| DB CPU | 40% | DB chưa quá tải |
| Thread count | 450 | Cao bất thường (Tomcat mặc định 200) |
| 300 thread WAITING ở HTTP client → Tax Service | | **I/O-bound**: thread bị giữ để chờ downstream |

> Kết luận phân loại: **API chậm nhưng CPU thấp** → nhìn thread dump, blocked I/O, pool, downstream latency.

## 2. Root-cause hypothesis (xếp theo khả năng)

1. **Tax Service chậm/quá tải** → mỗi request giữ thread rất lâu → thread pool bão hòa → request mới xếp hàng → p99 tăng vọt.
2. **HTTP client thiếu timeout** (hoặc timeout quá dài, vd. mặc định vô hạn) → thread chờ mãi.
3. **Connection pool của HTTP client quá nhỏ** (vd. Apache HttpClient `maxPerRoute=20`) → thread WAITING để *lấy connection*, không phải chờ response.
4. **Gọi Tax Service trong loop / tuần tự** (N+1 remote call, xem bài 07) → 1 request dashboard = N lần gọi.
5. **Retry storm**: retry không backoff làm Tax Service càng quá tải.

Cách phân biệt (3) với (1): stack trace dừng ở `PoolingHttpClientConnectionManager.leaseConnection` / `AbstractConnPool.getPoolEntryBlocking` → thiếu pool; dừng ở `SocketInputStream.read` → chờ response từ Tax Service.

## 3. Artifact cần lấy

| Artifact | Cách lấy | Để trả lời câu hỏi |
| --- | --- | --- |
| **Thread dump** ×3, cách nhau 5–10s | `jcmd <pid> Thread.print` / `jstack` | Thread kẹt ở đâu (pool lease hay socket read)? Có kéo dài liên tục không? |
| **JFR** 2–5 phút | `jcmd <pid> JFR.start duration=120s settings=profile filename=dash.jfr` | Socket Read events (host, duration), Thread Park, Monitor Blocked, latency theo method |
| **Metrics app** | Micrometer/Prometheus | `http_server_requests` p50/p95/p99, `tomcat.threads.busy`, `jvm.threads.states` |
| **Metrics HTTP client** | Micrometer `http.client.requests` | Latency/error rate gọi Tax Service, pool leased/pending/max |
| **Metrics Tax Service** | Dashboard phía Tax | p99, error rate, saturation của chính nó |
| **Tracing** | OpenTelemetry/Zipkin | Span nào chiếm phần lớn 8s, gọi bao nhiêu lần/request |
| **Log** | Log timeout/exception | `SocketTimeoutException`, `ConnectionPoolTimeoutException` |

> Profiler **không thay thế** metrics/log/tracing: metrics cho biết *có vấn đề ở đâu*, tracing cho biết *span nào chậm*, profiler/thread dump cho biết *code đang làm gì*.

## 4. Vì sao heap dump KHÔNG ưu tiên đầu tiên

- Heap **ổn định** → không có dấu hiệu leak/OOM; heap dump trả lời câu hỏi "ai giữ object", không liên quan thread chờ I/O.
- Heap dump **stop-the-world**, file rất lớn (bằng kích thước heap), có thể chứa **PII/lương** → rủi ro khi lấy trên production.
- Thread dump + JFR rẻ (overhead JFR `default` ~1%), trả lời đúng câu hỏi hơn.
- Chọn artifact theo symptom: memory tăng → heap dump; ở đây là I/O wait → thread dump/JFR.

## 5. Immediate mitigation (giờ đầu)

- Đặt **timeout** cho Tax client: connect 500ms, read 1–2s → giải phóng thread nhanh.
- **Circuit breaker** (Resilience4j) cho Tax Service → fail fast khi Tax lỗi > 50%.
- **Fallback/degrade**: dashboard trả partial data, field tax = `"unavailable"` hoặc giá trị cache gần nhất.
- **Bulkhead**: giới hạn số concurrent call tới Tax (vd. 50) để không ăn hết thread pool của toàn app.
- Báo đội Tax Service; nếu vừa deploy → cân nhắc rollback.
- **Không** tăng mù quáng thread pool lên 1000: chỉ dồn thêm tải vào Tax Service và tốn memory.

## 6. Long-term fix

- **Batch API**: `POST /tax-profiles:batch` thay vì gọi từng employee.
- **Cache** tax profile (ít thay đổi) với TTL + invalidation qua event.
- Gọi **song song có giới hạn** (WebClient `flatMap(..., 16)` hoặc virtual threads + semaphore).
- Precompute/denormalize dashboard bằng job/event, API chỉ đọc read-model.
- SLO + alert trên `http.client.requests` p99 Tax Service và `tomcat.threads.busy`.
- Load test (Gatling/k6) trước/sau fix, so sánh p99 & thread count.

## 7. Checklist

- [x] Profiler không thay thế metrics/log/tracing.
- [x] Chọn artifact theo symptom (I/O wait → thread dump + JFR, không phải heap dump).
- [x] Phân biệt CPU-bound / memory-bound / I/O-bound → đây là I/O-bound.
- [x] Kết luận dựa trên evidence (stack trace, metrics pool, tracing).

