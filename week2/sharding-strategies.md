# Sharding Strategies & Challenges

## Phần A — So sánh Strategy

| Tiêu chí | Hash-based | Range-based | Directory-based |
| --- | --- | --- | --- |
| Data distribution | Đều nhờ hash function | Có thể lệch nếu key phân bố không đều | Linh hoạt, admin tự quyết |
| Range query | Khó — phải fan-out tất cả shards | Hiệu quả — data liền kề cùng shard | Tuỳ cấu hình directory |
| Hot spot risk | Thấp | Cao — write mới dồn về shard cuối | Thấp — chủ động move hot key |
| Re-sharding complexity | Cao — modulo thay đổi → move nhiều data (consistent hashing giảm bớt) | Trung bình — chỉ tách/merge range bị ảnh hưởng | Thấp — cập nhật bảng lookup |
| Routing complexity | Thấp — hash + mod, stateless | Thấp — so sánh range boundary | Cao — phải tra lookup table, cần replicate để tránh SPOF |

## Phần B — Design Case: Multi-Tenant SaaS (5M tenants)

### 1. Shard Key: `tenantId`

Phần lớn query theo `tenantId` → single-shard lookup, data locality tốt, transaction trong tenant không cross-shard.

### 2. Strategy: Consistent Hashing + Directory Override

- **Default**: `consistentHash(tenantId) → shardId` — phân phối đều cho ~99% tenants.
- **Override**: Hot/enterprise tenants ghi vào directory → route sang dedicated shard.
- Không dùng range vì `tenantId` thường là UUID. Không dùng directory thuần vì 5M entries quá nặng.

### 3. Xử lý Hot Tenant

1. **Detect**: Monitor request/s per shard, alert khi > 80% capacity.
2. **Isolate**: Provision dedicated shard → dual-write → backfill → switch routing trong directory → cleanup shard cũ.
3. **Scale riêng**: Dedicated shard có thể thêm read replica / scale vertical độc lập.

### 4. Báo cáo Cross-Tenant

Dùng **CDC (Debezium) / ETL nightly** → đổ vào **Analytics Store** riêng (ClickHouse/BigQuery).  
Reporting Service query analytics store, không query trực tiếp shards → không ảnh hưởng production.

### 5. Re-sharding

Dùng consistent hashing + virtual nodes → thêm shard chỉ cần move ~20% data.

**4 phases (gần zero-downtime):**

| Phase | Hành động |
| --- | --- |
| Chuẩn bị | Provision shard mới, thêm virtual nodes vào ring |
| Migration | Dual-write + background backfill historical data |
| Cutover | Switch routing → shard mới, verify, tắt dual-write |
| Cleanup | Xoá data cũ sau grace period, cập nhật directory |

**Risk mitigation**: Dual-write + checksum verify trước cutover, giữ data cũ 7 ngày để rollback.

## Diagrams

### Routing Request

```
Client (tenantId)
       │
   API Gateway
       │
   Shard Router
   ├── Check directory cache → có override? → Dedicated Shard
   └── Không → consistentHash(tenantId) → Shard 1/2/3/.../N
```

### Hot Shard → Isolation

```
Before:                          After:
  Shard 1: ███░░░ 30%             Shard 1: ███░░░ 30%
  Shard 2: █████████ 90% ⚠️      Shard 2: ███░░░ 30% ✅
  Shard 3: ██░░░░ 20%             Shard 3: ██░░░░ 20%
                                   Shard 4: ██████ 60% (dedicated, scale riêng)
```
