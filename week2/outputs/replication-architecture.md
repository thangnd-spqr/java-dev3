# Replication Architecture – Hệ thống E-Commerce

## 1. Tổng quan

Tài liệu mô tả kiến trúc **Database Replication** cho hệ thống e-commerce, sử dụng mô hình **Single-Leader (Primary–Replica)** với PostgreSQL. Mục tiêu:

- **Read scaling**: tăng throughput cho các truy vấn đọc bằng cách phân tải sang nhiều replica.
- **High availability**: đảm bảo hệ thống vẫn hoạt động khi primary gặp sự cố.
- **Data durability**: dữ liệu được nhân bản trên nhiều node, giảm rủi ro mất dữ liệu.

---

## 2. Architecture Diagram

### 2.1 Sơ đồ tổng quan

```
                        ┌─────────────────────┐
                        │      Clients         │
                        │  (Web / Mobile App)  │
                        └─────────┬───────────┘
                                  │
                                  ▼
                        ┌─────────────────────┐
                        │   Load Balancer /    │
                        │   API Gateway        │
                        └─────────┬───────────┘
                                  │
                                  ▼
                  ┌───────────────────────────────┐
                  │     Application Layer          │
                  │  ┌───────────┐ ┌────────────┐  │
                  │  │  Order    │ │  Product   │  │
                  │  │  Service  │ │  Service   │  │
                  │  └─────┬─────┘ └─────┬──────┘  │
                  │        │             │         │
                  │  ┌─────▼─────────────▼──────┐  │
                  │  │   Read/Write Router      │  │
                  │  │   (DataSource Routing)   │  │
                  │  └──┬──────────────────┬────┘  │
                  └─────┼──────────────────┼───────┘
                        │                  │
            ┌───────────┘                  └────────────┐
            │ WRITE (INSERT/UPDATE/DELETE)    READ (SELECT) │
            ▼                                           ▼
   ┌─────────────────┐                     ┌────────────────────┐
   │   PostgreSQL    │   Replication Log   │  Read Load Balancer │
   │   PRIMARY       │ ──────────────────► │  (e.g. PgBouncer)  │
   │   (Leader)      │   (WAL Streaming)   └───┬──────────┬─────┘
   │                 │                         │          │
   │  - Xử lý mọi   │                         ▼          ▼
   │    WRITE        │              ┌──────────────┐ ┌──────────────┐
   │  - Ghi WAL log  │              │  REPLICA 1   │ │  REPLICA 2   │
   └─────────────────┘              │  (Follower)  │ │  (Follower)  │
                                    │              │ │              │
                                    │ - Phục vụ    │ │ - Phục vụ    │
                                    │   READ       │ │   READ       │
                                    │ - Hot standby│ │ - Hot standby│
                                    └──────────────┘ └──────────────┘
```

### 2.2 Replication Flow

```
Client WRITE request
        │
        ▼
    Primary (Leader)
        │
        ├──► Ghi vào WAL (Write-Ahead Log)
        │
        ├──► Commit transaction, trả response cho client
        │
        └──► Stream WAL records ──► Replica 1
                               ──► Replica 2
                                       │
                                       ▼
                              Apply WAL → data đồng bộ
```

> **Lưu ý**: Replication mặc định là **asynchronous**. Replica có thể bị **lag** (chưa có dữ liệu mới nhất) trong khoảng milliseconds → seconds.

---

## 3. Bảng Routing Read/Write

| **Loại Request**            | **Đi đến đâu**        | **Lý do**                                                                                                  |
|-----------------------------|------------------------|------------------------------------------------------------------------------------------------------------|
| Create order                | **Primary**            | Thao tác **WRITE** (INSERT). Chỉ primary mới chấp nhận ghi.                                               |
| Update payment status       | **Primary**            | Thao tác **WRITE** (UPDATE). Cập nhật trạng thái đơn hàng phải đi qua primary để đảm bảo consistency.     |
| Get product catalog         | **Replica 1 / 2**     | Thao tác **READ**. Catalog ít thay đổi, replica lag không ảnh hưởng. Tận dụng read scaling.                |
| Get order vừa tạo           | **Primary**            | **Read-after-write**. Đọc ngay sau khi ghi → phải đọc từ primary vì replica có thể chưa nhận được dữ liệu mới do replication lag. |
| View reporting dashboard    | **Replica 2**          | Thao tác **READ** nặng (aggregation, JOIN). Route sang replica riêng để tránh ảnh hưởng hiệu năng primary và replica phục vụ user. |

### 3.1 Routing Rules tổng quát

```
┌────────────────────────────────────────────────────┐
│                 ROUTING DECISION                    │
├────────────────────────────────────────────────────┤
│                                                    │
│  if (operation == WRITE) {                         │
│      → route to PRIMARY                            │
│  }                                                 │
│                                                    │
│  if (operation == READ && just_wrote) {            │
│      → route to PRIMARY  // read-after-write       │
│  }                                                 │
│                                                    │
│  if (operation == READ && is_heavy_report) {       │
│      → route to REPLICA 2  // dedicated reporting  │
│  }                                                 │
│                                                    │
│  if (operation == READ) {                          │
│      → route to REPLICA 1 or 2  // round-robin     │
│  }                                                 │
│                                                    │
└────────────────────────────────────────────────────┘
```

### 3.2 Giải thích Read-after-write

Khi client vừa tạo order xong và ngay lập tức gọi `GET /orders/{id}`:

```
T=0ms    Client POST /orders → PRIMARY ghi thành công, trả orderId=123
T=5ms    Client GET /orders/123 → Nếu route sang REPLICA:
              ⚠️ Replica có thể chưa nhận WAL → trả 404 hoặc data cũ!
         → Phải route sang PRIMARY để đảm bảo đọc được data mới nhất.
```

**Giải pháp**: Application tự detect "read-after-write" (ví dụ: trong cùng session, nếu vừa write thì read tiếp theo vẫn đi primary trong khoảng N giây).

---

## 4. Failover Flow khi Primary Down

### 4.1 Sơ đồ Failover (7 bước)

```
 Bước 1          Bước 2           Bước 3          Bước 4
┌──────┐      ┌──────────┐     ┌──────────┐    ┌──────────────┐
│Primary│      │Health    │     │ Quorum   │    │  Chọn        │
│ DOWN  │─────►│Check     │────►│ Confirm  │───►│  Replica     │
│  ✗    │      │phát hiện │     │ primary  │    │  phù hợp     │
└──────┘      │ failure  │     │ thực sự  │    │  nhất        │
               └──────────┘     │ down     │    └──────┬───────┘
                                └──────────┘           │
                                                       ▼
 Bước 7          Bước 6           Bước 5
┌──────────┐   ┌──────────┐    ┌──────────────┐
│ Monitor  │   │ Route    │    │  Promote     │
│ & verify │◄──│ traffic  │◄───│  Replica 1   │
│ new      │   │ sang new │    │  thành       │
│ primary  │   │ primary  │    │  PRIMARY mới │
└──────────┘   └──────────┘    └──────────────┘
```

### 4.2 Chi tiết từng bước

#### **Bước 1: Phát hiện Primary down**
- Health check daemon (ví dụ: Patroni, PgPool-II, hoặc custom) gửi heartbeat / ping đến Primary mỗi **5 giây**.
- Nếu không nhận phản hồi sau **3 lần liên tiếp** (15 giây) → đánh dấu primary là **suspected failure**.

#### **Bước 2: Xác nhận failure (Quorum check)**
- Tránh **split-brain**: không promote replica ngay khi chỉ 1 monitor thấy primary down.
- Nhiều monitor nodes (hoặc etcd/ZooKeeper cluster) phải **đồng thuận** rằng primary thực sự không khả dụng.
- Đợi thêm **timeout confirmation** (ví dụ: 10 giây) để loại trừ network partition tạm thời.

#### **Bước 3: Chọn Replica phù hợp nhất**
- Tiêu chí chọn replica để promote:

  | Tiêu chí                  | Mô tả                                          |
  |---------------------------|-------------------------------------------------|
  | **Replication lag thấp nhất** | Replica nào có dữ liệu gần nhất với primary    |
  | **WAL position mới nhất**    | So sánh `pg_last_wal_receive_lsn()` giữa các replica |
  | **Priority cấu hình**        | Replica được gán priority cao hơn (nếu có)      |
  | **Tình trạng healthy**        | Replica đang hoạt động bình thường, không overloaded |

- Trong ví dụ này: **Replica 1** được chọn vì có WAL position mới nhất và lag thấp nhất.

#### **Bước 4: Promote Replica thành Primary mới**
- Thực thi `pg_promote()` trên Replica 1 (PostgreSQL 12+) hoặc tạo trigger file.
- Replica 1 thoát chế độ **hot standby** → chuyển sang chế độ **read-write**.
- Replica 1 trở thành **Primary mới**.

#### **Bước 5: Cập nhật routing (Re-route traffic)**
- Cập nhật **connection string / DNS** để application trỏ write traffic sang Primary mới (Replica 1 cũ).
- Cách thực hiện tùy hạ tầng:

  | Phương pháp               | Mô tả                                                  |
  |---------------------------|---------------------------------------------------------|
  | **Virtual IP (VIP)**      | Chuyển VIP từ primary cũ sang primary mới               |
  | **DNS update**            | Cập nhật DNS record `primary.db.internal` sang IP mới   |
  | **Connection pooler**     | PgBouncer/HAProxy tự động cập nhật backend server       |
  | **Application config**    | Hot-reload datasource config trong application          |

#### **Bước 6: Reconfigure Replica 2**
- Replica 2 (vẫn hoạt động) cần chuyển **replication source** từ primary cũ → primary mới.
- Cập nhật `primary_conninfo` trên Replica 2 để stream WAL từ Primary mới.
- Restart hoặc reload replication trên Replica 2.

#### **Bước 7: Giám sát và xác nhận**
- Kiểm tra Primary mới hoạt động bình thường (chấp nhận write).
- Kiểm tra Replica 2 đã replicate từ Primary mới thành công.
- Monitor **replication lag** trên tất cả replica.
- Ghi log sự kiện failover để review sau.
- **(Tùy chọn)** Khi primary cũ recover → join lại cluster với vai trò **Replica** (không tự động trở thành Primary).

---

## 5. Lưu ý quan trọng

### 5.1 Replication KHÔNG giải quyết write bottleneck

```
  Trước replication          Sau replication
  ┌─────────┐                ┌─────────┐
  │ Primary │                │ Primary │ ← vẫn là nơi duy nhất xử lý WRITE
  │ R + W   │                │ W only  │
  └─────────┘                └────┬────┘
                                  │ WAL
                             ┌────┴────┐
                             ▼         ▼
                        ┌────────┐ ┌────────┐
                        │Replica1│ │Replica2│ ← chỉ phục vụ READ
                        │ R only │ │ R only │
                        └────────┘ └────────┘
```

- Write vẫn **chỉ đi qua 1 primary** → nếu write là bottleneck, cần giải pháp khác (sharding, partitioning).
- Replication chỉ **scale read**, không scale write.

### 5.2 Replica Lag

- Replica **không luôn có dữ liệu mới nhất** do replication là asynchronous.
- Lag thông thường: **milliseconds** (mạng nội bộ tốt).
- Lag có thể tăng khi: replica bị quá tải, network chậm, hoặc write workload quá lớn.
- **Synchronous replication** giảm lag nhưng tăng latency cho write (primary phải chờ replica xác nhận).

### 5.3 Split-brain

- Tình huống nguy hiểm: primary cũ chưa thực sự down (chỉ network partition) → 2 nodes cùng nhận write.
- **Giải pháp**: Sử dụng fencing (STONITH) để đảm bảo primary cũ bị tắt trước khi promote replica.

---

## 6. Tóm tắt

| Thành phần           | Vai trò                                      |
|----------------------|----------------------------------------------|
| **Primary (Leader)** | Xử lý mọi thao tác WRITE, stream WAL log     |
| **Replica 1**        | Phục vụ READ, hot standby, ưu tiên promote    |
| **Replica 2**        | Phục vụ READ (reporting), hot standby backup   |
| **Read/Write Router**| Điều hướng query đến đúng node                |
| **Health Monitor**   | Phát hiện failure, trigger failover tự động    |

