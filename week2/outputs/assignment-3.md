# Assignment 3: Vì sao báo cáo doanh thu cuối tháng bị chậm 6 tiếng và làm sập cả hệ thống bán hàng?

**Topics áp dụng:** Replication Strategies (Sync/Async), Database Sharding

---

## Q1: Vấn đề gốc rễ (root cause) là gì? Có phải chỉ là "query chậm" không?

**Không chỉ là "query chậm".** Có 2 nguyên nhân gốc rễ sâu xa hơn:

1. **Không tách biệt workload OLTP và OLAP:** Hệ thống dùng chung 1 database duy nhất cho cả traffic bán hàng (write/OLTP) và báo cáo (read-heavy aggregate/OLAP). Khi query báo cáo chạy, nó chiếm hết CPU & I/O → write của khách hàng bị timeout.

2. **Không sharding/partitioning dữ liệu:** 500 triệu dòng nằm gọn trong 1 database instance duy nhất, không có bất kỳ phân chia nào. Query báo cáo buộc phải **full table scan** toàn bộ 500 triệu dòng → chậm 6 tiếng là tất yếu.

> **Tóm lại:** Root cause = thiếu **read replica** để tách workload + thiếu **sharding/partitioning** để giảm lượng data cần quét.

---

## Q2: Giải pháp "tách Replica riêng cho reporting" có giải quyết triệt để không?

**Giải quyết được một phần, nhưng KHÔNG triệt để.**

### ✅ Giải quyết được:
- Tách workload reporting sang replica riêng → **Primary không còn bị ảnh hưởng** → khách hàng mua hàng bình thường, không bị timeout.
- Hệ thống bán hàng (write) hoạt động ổn định trong giờ cao điểm.

### ❌ Chưa giải quyết được:
- Query báo cáo **vẫn chậm 6 tiếng** vì replica cũng chứa 500 triệu dòng, vẫn phải full scan.
- Khi data tiếp tục tăng (đã x10 trong 1 năm), replica cũng sẽ bị quá tải.
- Không giải quyết được vấn đề **write bottleneck** trên Primary khi traffic write tiếp tục tăng (replication chỉ scale read, không scale write).

> **Kết luận:** Replica reporting giải quyết vấn đề "sập hệ thống bán hàng", nhưng cần kết hợp thêm **sharding/partitioning** để giải quyết vấn đề "query chậm 6 tiếng" và scale write.

---

## Q3: Chọn shard key nào cho bảng orders? Phân tích `customerId`, `region`, `created_at`

| Tiêu chí | `customerId` | `region` | `created_at` |
|---|---|---|---|
| **Phân phối dữ liệu** | ✅ Đều (nhiều customer) | ❌ Không đều (ít region, có thể 1 region chiếm 80% đơn) | ⚠️ Đều theo thời gian, nhưng shard mới nhất luôn hot |
| **Phân phối traffic** | ✅ Đều | ❌ Lệch (hot region) | ❌ Lệch (write dồn vào shard hiện tại) |
| **Query "lấy orders của 1 customer"** | ✅ Single-shard lookup | ❌ Scatter-gather (query tất cả shard) | ❌ Scatter-gather |
| **Query báo cáo GROUP BY region** | ❌ Scatter-gather | ✅ Single-shard per region | ⚠️ Scatter-gather hoặc single-shard tùy range |
| **Write scaling** | ✅ Tốt | ❌ Kém (hot shard) | ❌ Kém (hot shard hiện tại) |

### 🏆 Đề xuất: **`customerId`**

**Lý do:**
- Phân phối dữ liệu và traffic **đều nhất** vì số lượng customer rất lớn.
- Query phổ biến nhất của OLTP (lấy/tạo order cho 1 customer) chỉ cần hit **1 shard duy nhất**.
- Write traffic được phân tán đều → giải quyết write bottleneck.
- Query báo cáo (ít thường xuyên hơn) có thể chạy trên **replica riêng** nên scatter-gather chấp nhận được.

---

## Q4: Sharding theo thời gian (mỗi tháng 1 shard) – query báo cáo có nhanh hơn không? Đánh đổi gì?

### ✅ Có nhanh hơn cho báo cáo cuối tháng:
- Query `WHERE created_at BETWEEN '2026-09-01' AND '2026-09-30'` chỉ cần quét **đúng 1 shard** (shard tháng 9) thay vì 500 triệu dòng.
- Dữ liệu mỗi shard nhỏ hơn nhiều → query nhanh hơn đáng kể.

### ❌ Đánh đổi (trade-off):

| Đánh đổi | Giải thích |
|---|---|
| **Hot shard** | Tất cả write đều dồn vào shard tháng hiện tại → shard đó trở thành bottleneck, không scale write |
| **Cross-shard query** | Query "lấy tất cả orders của 1 customer" phải scatter-gather qua **tất cả shard** (mỗi tháng 1 shard) → rất chậm |
| **Shard management phức tạp** | Mỗi tháng phải tạo shard mới, shard cũ cần archive/cleanup |
| **Không đều dữ liệu** | Tháng cao điểm (Black Friday, Tết) có thể gấp 5-10x tháng bình thường |

> **Kết luận:** Time-based sharding tốt cho **reporting/analytics** nhưng kém cho **OLTP** vì hot shard và cross-shard query.

---

## Q5: Sync hay Async replication phù hợp cho Replica dùng cho reporting? Vì sao?

### 🏆 Đề xuất: **Asynchronous Replication**

**Lý do:**

1. **Báo cáo không cần real-time tuyệt đối:** Dữ liệu trễ vài giây đến vài phút là hoàn toàn chấp nhận được cho báo cáo doanh thu cuối tháng. Kế toán không cần số liệu chính xác đến từng mili-giây.

2. **Không ảnh hưởng write performance:** Async replication cho phép Primary trả về `success` ngay sau khi ghi local, không cần chờ replica ACK → write latency thấp, không làm chậm giao dịch bán hàng.

3. **Primary ổn định hơn:** Nếu dùng Sync, khi replica reporting chạy query nặng và bị chậm → có thể làm chậm ACK → ảnh hưởng ngược lại write trên Primary. Async loại bỏ rủi ro này.

4. **Chấp nhận replica lag:** Với use case reporting, lag vài giây không ảnh hưởng kết quả. Ngược lại, nếu dùng Sync chỉ để đảm bảo consistency cho báo cáo thì chi phí (latency, complexity) không xứng đáng.

| Tiêu chí | Sync | Async (✅ chọn) |
|---|---|---|
| Write latency trên Primary | Cao (chờ ACK) | Thấp |
| Replica lag | Không có | Có (vài giây) |
| Ảnh hưởng Primary khi replica chậm | Có | Không |
| Phù hợp cho reporting | ❌ Quá mức cần thiết | ✅ Vừa đủ |
