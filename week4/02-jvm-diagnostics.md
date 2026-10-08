# 02 - JVM Diagnostics

## Scenario A — Memory leak

> Payroll export tăng memory từ 1GB lên 6GB. Sau GC, heap vẫn gần đầy. Cuối cùng service OOM.

**Lấy artifact nào trước**

1. GC log / heap metrics: xác nhận heap **sau Full GC** không giảm, tức là leak thật.
2. Class histogram, chụp 2–3 lần: xem class nào tăng liên tục.
3. Heap dump: lấy trên instance đã rút khỏi traffic, hoặc dùng file từ `HeapDumpOnOutOfMemoryError`.

**Quan sát gì**

- Histogram: `byte[]`, `String`, `ArrayList`, `PayrollRecord`, `XSSFRow`/`XSSFCell` tăng không ngừng.
- Heap dump (MAT): **dominator tree** có 1 object có retained size rất lớn. **Path to GC Roots** cho biết ai đang giữ reference (static cache, `ThreadLocal`…).
- GC log: Full GC chạy liên tục, heap sau GC ~90–100%.

**Root cause hypothesis**

Export load toàn bộ dữ liệu vào memory (`findAll()` vào `List`, build `XSSFWorkbook`/`ByteArrayOutputStream` trong RAM), hoặc lưu kết quả vào static cache không có eviction.

**Mitigation ngắn hạn**

- Restart instance (sau khi đã lấy dump).
- Giới hạn số export chạy đồng thời.
- Bật `-XX:+HeapDumpOnOutOfMemoryError`.

**Fix dài hạn**

- Đọc DB theo page/cursor và ghi file streaming (`SXSSFWorkbook`, CSV).
- Chuyển export thành async job.
- Cache có giới hạn kích thước và TTL; gọi `ThreadLocal.remove()` trong `finally`.

---

## Scenario B — Thread starvation

> API chậm nhưng CPU chỉ 30%. Thread pool 200 threads đều đang chờ HTTP call đến Tax Service.

**Lấy artifact nào trước**

1. **Thread dump**, lấy 3 lần cách nhau 5–10s.
2. Metrics: latency của Tax Service, số thread active, số request đang chờ trong HTTP connection pool.
3. Không cần heap dump, vì CPU thấp và request treo không phải vấn đề memory.

**Quan sát gì**

- Phần lớn worker thread có cùng một stack ở cả 3 lần dump:
  - `RUNNABLE` tại `socketRead`: đang chờ response từ Tax Service.
  - `WAITING (parking)` tại `PoolingHttpClientConnectionManager.lease...`: connection pool đã cạn.
- Không có `BLOCKED` hay deadlock.

**Root cause hypothesis**

Tax Service chậm, trong khi client không có timeout (hoặc timeout quá dài). Mỗi request giữ 1 thread rất lâu, nên cả 200 thread đều bị chiếm. Request mới phải xếp hàng, vì vậy API chậm dù CPU vẫn rảnh.

**Mitigation ngắn hạn**

- Đặt connect/read/connection-request timeout ngắn rồi rolling restart.
- Fail fast hoặc fallback sang tax rate trong cache.
- Rate limit các endpoint phụ thuộc Tax Service.

**Fix dài hạn**

- Bắt buộc có timeout cho mọi outbound call.
- Thêm circuit breaker và bulkhead (Resilience4j) để tách pool riêng cho Tax Service.
- Retry có giới hạn, kèm backoff.
- Cache kết quả tax.
- Alert theo trạng thái thread pool và connection pool.

---

## Checklist: khi nào lấy Heap dump, khi nào lấy Thread dump

**Thread dump**

- [ ] Request treo hoặc chậm nhưng CPU thấp.
- [ ] CPU cao bất thường (kết hợp `top -H` để tìm thread nóng).
- [ ] Nghi deadlock hoặc lock contention (nhiều thread `BLOCKED`).
- [ ] Thread pool hoặc connection pool bị cạn.

**Heap dump**

- [ ] Heap sau Full GC vẫn cao và tăng dần.
- [ ] Gặp `OutOfMemoryError: Java heap space`.
- [ ] Histogram có class tăng bất thường nhưng chưa rõ ai giữ reference.
- [ ] Đã rút instance khỏi traffic, còn đủ disk (≥ heap size).

**Lưu ý**

- Heap dump **không** phải công cụ đầu tiên. Xem metrics và GC log trước.
- Heap dump gây pause JVM và **chứa dữ liệu nhạy cảm** (PII, token, lương). Cần lưu file ở nơi an toàn và xóa sau khi phân tích.

---

## Runbook: lệnh chẩn đoán an toàn

Các bước xếp theo thứ tự từ ít ảnh hưởng đến nhiều ảnh hưởng.

```bash
# 1. Xác định process
jcmd -l
jcmd <pid> VM.flags

# 2. Quan sát nhẹ
jstat -gcutil <pid> 1000 10
jcmd <pid> GC.heap_info

# 3. Thread dump (an toàn, lấy 3 lần)
for i in 1 2 3; do jcmd <pid> Thread.print -l > td-$i.txt; sleep 5; done

# 4. Class histogram (có thể trigger Full GC)
jcmd <pid> GC.class_histogram > histo.txt

# 5. JFR (overhead thấp)
jcmd <pid> JFR.start duration=120s filename=/tmp/diag.jfr

# 6. Heap dump (tác động cao: rút instance khỏi LB, kiểm tra disk trước)
jcmd <pid> GC.heap_dump /dumps/heap.hprof
chmod 600 /dumps/heap.hprof
```
