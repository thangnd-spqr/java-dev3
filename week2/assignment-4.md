# Assignment 4: Vì sao 1 khách hàng VIP làm chậm cả hệ thống?

**Topics:** Sharding Strategies (Hash vs Range), Sharding Challenges (Hot Spot, Re-sharding)

---

## Bối cảnh

Hệ thống SaaS sharding theo **Range-based** trên `tenantId`:
- Shard 1: tenantId 1–100,000 → **CPU 95%** (quá tải)
- Shard 2: tenantId 100,001–200,000 → CPU 15%
- Shard 3: tenantId 200,001–300,000 → CPU 15%

**ABC Corp** (tenantId=50,000): 50,000 nhân viên, traffic gấp **200x** tenant thường → dồn hết vào Shard 1 → **Hot Spot + Noisy Neighbor**.

---

## 2. Câu hỏi & Trả lời

### Q1: Range-based sharding công bằng số lượng tenant, vậy tại sao vẫn quá tải?

**Có** công bằng về số lượng (mỗi shard ~100,000 tenant), nhưng **không công bằng về traffic**.

> **Data distribution ≠ Traffic distribution.** Range sharding chỉ cân bằng số key, không cân bằng workload. ABC Corp 1 mình tạo traffic = 200 tenant thường → Shard 1 chết, đó là **Hot Spot**.

---

### Q2: Đổi sang Hash-based sharding (hash(tenantId) % 3) có giải quyết không?

**Không.** Hash chỉ đổi ABC Corp sang shard khác, nhưng **toàn bộ traffic vẫn dồn vào 1 shard**.

```
Range:  Shard 1 [ABC Corp + 99,999 tenants] → CPU 95%
Hash:   Shard X [ABC Corp + ~100,000 tenants] → CPU vẫn ~95%
```

Hash sharding giải quyết được hot spot do **sequential ID**, nhưng không giải quyết được **skewed workload** (1 tenant quá lớn).

---

### Q3: Đổi thuật toán sharding có đủ không, hay cần kiến trúc khác?

**Không đủ.** Bất kể Hash/Range/Directory — nếu shard key là `tenantId` thì 1 tenant = 1 shard. Cần thêm:

| Giải pháp | Mô tả |
|-----------|-------|
| **Dedicated Shard** | Tách tenant lớn ra shard riêng |
| **Sub-sharding** | Shard data trong tenant theo `userId` |
| **Tiered Architecture** | Enterprise/Business/Free → infra khác nhau |
| **Caching + Read Replica** | Giảm read load bằng Redis + replica |
| **Rate Limiting** | Giới hạn traffic per tenant, bảo vệ shared shard |

---

### Q4: Đề xuất giải pháp cho tenant lớn — có bao nhiêu hướng?

**5 hướng**, triển khai theo ưu tiên:

| Ưu tiên | Giải pháp | Timeline |
|---------|-----------|----------|
| 🔴 Ngay | Rate Limiting + Caching | 1-2 tuần |
| 🟡 Ngắn hạn | **Dedicated Shard cho ABC Corp** ⭐ | 1-2 tháng |
| 🟢 Dài hạn | Tiered Architecture | 3-6 tháng |

**Dedicated Shard** là giải pháp khuyến nghị: ABC Corp có shard riêng, scale độc lập, không ảnh hưởng tenant khác.

---

### Q5: Migrate ABC Corp sang shard riêng mà KHÔNG DOWNTIME?

**Chiến lược: Dual-Write + Gradual Cutover**

```
Phase 1 (Copy)      → Snapshot data ABC Corp từ Shard 1 → Shard 4
Phase 2 (Dual-Write)→ Write vào CẢ Shard 1 + Shard 4, read từ Shard 1
Phase 3 (Cutover)   → Chuyển read sang Shard 4, vẫn dual-write (safety net)
Phase 4 (Cleanup)   → Chỉ write Shard 4, xóa data ABC trên Shard 1
```

**Điểm quan trọng:**
- Backfill data bị lỡ giữa Phase 1 → 2
- Verify consistency (row count + checksum) trước cutover
- Giữ data trên Shard 1 thêm 1-2 tuần để có thể rollback
- Dùng async queue (Kafka) để retry nếu dual-write fail

---

## Tổng kết

| Concept | Bài học |
|---------|--------|
| **Range Sharding** | Cân bằng data ≠ cân bằng traffic |
| **Hash Sharding** | Không fix được "elephant tenant" |
| **Hot Spot** | Do workload không cân xứng, không phải do thuật toán |
| **Giải pháp** | Dedicated shard + Tiered architecture |
| **Migration** | Dual-write + gradual cutover = zero-downtime |
