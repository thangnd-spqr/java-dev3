# 09 — Kế hoạch Hiện đại hóa Hệ thống Legacy HR (Strangler Fig & Tech Debt)

> **Nguyên tắc cốt lõi**: **Tuyệt đối không Big-Bang Rewrite** monolith 8 năm tuổi. Thay thế dần từng capability theo mẫu **Strangler Fig**: bọc hệ thống cũ bằng Facade/Seam, khóa hành vi bằng Characterization Test, chuyển đổi từng phần có so sánh kết quả (shadow/parallel run), luôn giữ đường rollback tức thì và chỉ xóa code cũ khi đã chứng minh ổn định.

## 1. Đánh giá Legacy & Lựa chọn Capability

| Capability | Hiện trạng & Đau đớn | Độ phức tạp / Coupling | Quyết định & Thứ tự |
| --- | --- | --- | --- |
| **Notification (Email)** | Gửi SMTP đồng bộ trong HTTP request → treo thread, tăng latency, lỗi mail làm hỏng giao dịch chính | **Thấp** — Leaf dependency (chỉ nhận lệnh gửi, không sở hữu state nghiệp vụ lõi) | **TÁCH ĐẦU TIÊN (Phase 1)** — Quick win, rủi ro nghiệp vụ thấp, giải phóng request thread ngay |
| **Reporting** | Query tổng hợp nặng chạy thẳng trên OLTP DB chính → gây lock bảng và nghẽn CPU giờ cao điểm | **Trung bình** — Read-only nhưng join nhiều bảng trên Shared DB | **Tách thứ hai (Phase 2)** — Offload sang Read Replica / CDC Read Model trước khi đụng write path |
| **Payroll** | **Hay thay đổi nhất** (luật thuế, BHXH, công thức), nhưng không có test, chọc thẳng bảng `employees`, `attendance` | **Rất cao** — Rủi ro tài chính/pháp lý cực lớn; sai 1 đồng lương là sự cố nghiêm trọng | **KHÔNG TÁCH ĐẦU TIÊN (Phase 3)** — Phải viết Characterization Test & cô lập boundary trong monolith trước |
| **Core Employee / Auth** | Dữ liệu gốc (`employees`, `departments`) được mọi nơi join trực tiếp | **Cực cao** — Fan-in lớn nhất toàn hệ thống | **Giữ lại cuối cùng** — Chỉ tách sau khi các vệ tinh đã ngừng truy cập trực tiếp bảng chung |

- **Vì sao chọn Notification làm capability đầu tiên?** Boundary rõ ràng nhất (luồng 1 chiều), không làm sai lệch dữ liệu lương/nhân sự nếu lỗi, giúp đội ngũ thiết lập trọn vẹn đường ống hiện đại hóa (Outbox/Message Broker, CI/CD, Observability, Feature Flag, Rollback) với rủi ro thấp nhất.
- **Vì sao KHÔNG tách Payroll đầu tiên dù hay thay đổi?** Tách một module tính tiền phức tạp khi **chưa có test đủ tốt** và đang **dính chặt Shared DB** sẽ biến bug ngầm thành lỗi sản xuất không kiểm soát được. Với Payroll, bước 1 bắt buộc là dựng **lưới an toàn (Characterization Tests)** và gom logic vào một module có interface rõ ngay trong monolith.

## 2. Safety Net trước khi tách (Characterization & Contract Tests)

1. **Characterization Tests (Golden Master) cho Payroll & luồng chính**:
   - Trích xuất tập dữ liệu thực tế đã ẩn danh của 3–6 kỳ lương gần nhất (bao gồm cả case biên: nghỉ thai sản, truy lĩnh, nghỉ việc giữa tháng, thuế lũy tiến).
   - Chạy qua hệ thống Legacy để chụp lại toàn bộ output (bảng lương, phiếu lương, bút toán) làm **Golden Master baseline**. Mọi thay đổi/tách service về sau phải cho ra kết quả khớp 100% với baseline này (kể cả các quy tắc làm tròn đặc thù đang được kế toán chấp nhận).
2. **Seam & Contract Tests**: Tạo interface (`NotificationPort`, `PayrollCalculationPort`) tại điểm nối trong Monolith; viết consumer-driven contract test và kiểm tra kiến trúc (ArchUnit) chặn viết thêm query join chéo sang các bảng dự kiến tách.

## 3. Luồng Strangler Fig & Facade/Gateway Routing

```text
Client / Monolith Caller
        │
        ▼
[ API Gateway / Internal Strangler Facade (Feature Flag & Traffic Router) ]
        ├─── 1. Legacy Path (100% -> 90% -> 0%) ───► [ Monolith Module ]
        └─── 2. Modern Path (Shadow -> 10% -> 100%) ► [ Extracted Service ]
                                                            │
                                              [ Comparator / Result Diff ]
```

**Quy trình 8 bước chuyển đổi tuần tự (áp dụng cho từng capability):**
1. **Xác định Seam & dựng Facade**:
   - Với API ngoài (Reporting, Payroll API): Đặt **API Gateway / Reverse Proxy** phía trước Monolith để định tuyến theo URI/Header/Tenant.
   - Với luồng nội bộ (Notification): Đặt `NotificationFacade` trong Monolith; thay vì gọi SMTP trực tiếp, ghi sự kiện vào bảng `outbox_events` trong cùng transaction.
2. **Xây dựng Service mới độc lập**: Có datastore riêng, không kết nối trực tiếp vào các bảng nghiệp vụ của Monolith.
3. **Shadow / Parallel Run (So sánh kết quả cũ vs mới)**:
   - *Notification*: Bật chạy song song, Service mới render nội dung email vào **dry-run sink** để đối chiếu người nhận, tiêu đề, nội dung với email của Monolith mà không gửi trùng cho nhân viên.
   - *Payroll (Phase 3)*: Khi chạy kỳ lương trên Monolith, phát sự kiện sang Payroll Service mới tính toán ngầm (shadow mode) và chạy bộ **Result Comparator** đối chiếu từng dòng lương (`diff_count == 0`).
4. **Canary Cutover (Chuyển traffic dần)**: Dùng feature flag theo `tenantId`/`departmentId` chuyển dịch: **1% (nội bộ) → 10% → 50% → 100%**.
5. **Bake Period & Decommission**: Giữ ổn định qua ít nhất **2 chu kỳ nghiệp vụ** (2 kỳ lương liên tiếp không phát sinh diff), sau đó xóa code cũ và bỏ nhánh routing legacy trong Monolith.

## 4. Chiến lược Di chuyển Dữ liệu (Shared DB → Database-per-Service)

> **Nguyên tắc**: **Không dùng Dual Write ở tầng ứng dụng** (ghi 2 DB không có 2PC sẽ gây lệch dữ liệu khi 1 bên lỗi). Sử dụng **Transactional Outbox + CDC (Debezium)** kết hợp mẫu **Expand–Contract**.

1. **Logical Isolation trong Shared DB**: Tách quyền truy cập bằng DB schema/role riêng; thay thế các câu `JOIN` trực tiếp bằng lời gọi qua API/Facade hoặc dữ liệu đọc từ projection.
2. **Backfill + CDC Sync (Monolith vẫn là Source of Truth)**:
   - Tạo DB riêng cho Service mới. Chạy job **Backfill** (idempotent, chia batch theo PK) để nạp dữ liệu lịch sử.
   - Bật **CDC (Debezium)** đọc binlog/WAL từ DB Monolith đẩy sang Service mới để bắt kịp các thay đổi phát sinh trong lúc backfill.
3. **Read Reconciliation**: Chạy job đối soát định kỳ (row count, tổng tiền lương hash/checksum) giữa DB cũ và DB mới cho đến khi độ lệch = `0`.
4. **Write Cutover + Reverse CDC**: Chuyển luồng ghi sang Service mới (trở thành Source of Truth). Trong suốt giai đoạn chuyển tiếp, bật **Reverse CDC** đồng bộ ngược dữ liệu từ DB mới về bảng cũ của Monolith để các báo cáo cũ chưa kịp sửa vẫn đọc được và **bảo toàn khả năng rollback không mất dữ liệu**.
5. **Contract**: Khi toàn bộ reader/writer trên Monolith đã ngừng dùng bảng cũ, thu hồi quyền, backup và xóa bảng legacy.

## 5. Kế hoạch Rollback (Traffic & Data)

| Tầng | Điều kiện kích hoạt (Auto/Manual Trigger) | Hành động Rollback | Thời gian (RTO / RPO) |
| --- | --- | --- | --- |
| **Traffic / Facade** | Error 5xx > 1%, p99 latency > 2× baseline, hoặc queue lag tăng đột biến | Chuyển Feature Flag / Gateway weight về `100% Legacy` (không cần redeploy) | **RTO < 1 phút**, RPO = 0 |
| **Shadow / Logic** | Phát hiện `payroll_diff_total > 0` hoặc sai lệch template email trong giai đoạn Canary | Tự động ngắt luồng ghi của Service mới, giữ nguyên kết quả từ Monolith | Tức thì, không ảnh hưởng user |
| **Database (Sau Write Cutover)** | Sự cố nghiêm trọng ở DB mới trong thời gian Bake Period | Đợi Reverse CDC drain hết event (< vài giây), bật lại quyền ghi trên Monolith, đóng luồng ghi Service mới | **RTO < 5 phút, RPO = 0** (nhờ Reverse CDC) |

## 6. Danh mục & Ưu tiên Nợ Kỹ thuật (Technical Debt Register)

Ưu tiên theo ma trận **Tác động kinh doanh & Tần suất thay đổi (Churn) × Rủi ro hệ thống**, không dọn nợ cảm tính ở các vùng code ít thay đổi:

| Mã | Khoản nợ kỹ thuật | Hệ quả hiện tại | Tác động | Effort | Ưu tiên | Kế hoạch xử lý |
| --- | --- | --- | --- | --- | --- | --- |
| **TD-01** | Thiếu test tự động ở module Payroll hay thay đổi | Sợ sửa code, mỗi lần đổi luật thuế dễ gây lỗi dây chuyền | Chí mạng | Trung bình | **P0 (Làm ngay)** | Viết Characterization Tests (Golden Master) trước mọi bước refactor/tách |
| **TD-02** | Gửi email đồng bộ trong HTTP request | Request chậm (2–5s), SMTP treo làm lỗi cả thao tác nhân sự | Cao | Thấp | **P0 (Phase 1)** | Thay bằng Transactional Outbox + tách Notification Service bất đồng bộ |
| **TD-03** | Reporting query nặng chạy chung OLTP DB | Đơ hệ thống vào cuối tháng khi HR/Kế toán xuất báo cáo | Cao | Thấp–TB | **P1 (Phase 2)** | Điều hướng toàn bộ query báo cáo sang Read Replica + xây bảng tổng hợp (Materialized View/CDC) |
| **TD-04** | Payroll coupling trực tiếp vào bảng chung | Không thể deploy độc lập, khó mở rộng khi tính lương hàng loạt | Cao | Cao | **P2 (Phase 3)** | Đóng gói boundary trong monolith → Shadow run → Tách Payroll Service + CDC data |
| **TD-05** | Code cũ ở các màn hình hành chính ít thay đổi | Code xấu nhưng ổn định, hiếm khi sửa | Thấp | Cao | **P3 (Hoãn)** | **Chấp nhận sống chung**; không tốn nguồn lực viết lại khi không mang lại giá trị kinh doanh |

## 7. Rủi ro & Chỉ số Thành công (Risks & Success Metrics)

| Rủi ro chính | Biện pháp giảm thiểu | Chỉ số thành công (Success Metrics) | Mục tiêu cụ thể |
| --- | --- | --- | --- |
| **Tạo ra Distributed Monolith** (tách service nhưng vẫn gọi sync chồng chéo & chung DB) | Cấm chia sẻ DB trực tiếp; giao tiếp qua async event/outbox; kiểm soát bằng contract test | **Data & Logic Parity (`diff_count`)** | **100% khớp (`0` sai lệch)** về số liệu lương qua 2 kỳ chạy song song trước khi cắt hẳn |
| **Lệch dữ liệu khi đồng bộ CDC** (mất thứ tự, lặp bản ghi) | Thiết kế consumer idempotent (dựa trên `event_id`/`version`) + job đối soát tự động mỗi giờ | **API Latency (p95/p99)** của các thao tác có gửi thông báo | Giảm từ **2.5s xuống < 200ms** sau khi tách Notification bất đồng bộ |
| **Migration kéo dài gây kiệt sức**, hệ thống tồn tại 2 nơi mãi mãi | Định nghĩa rõ Definition of Done cho từng capability: **chỉ hoàn thành khi đã xóa code cũ trong Monolith** | **Tải OLTP DB chính (CPU / Lock wait)** giờ cao điểm báo cáo | Giảm **≥ 40% CPU** và **0 lock timeout** do reporting query trên DB chính |
| **Sai sót khi tính lương** do thiếu edge case | Bắt buộc vượt qua bộ Golden Master từ dữ liệu thật 6 tháng trước khi chuyển dù chỉ 1% traffic thật | **Lead Time & Tần suất deploy** thay đổi luật lương/thuế | Rút ngắn từ **2 tuần xuống < 2 ngày**; lỗi hồi quy sau phát hành = **0** |

## Checklist hoàn thành

- [x] **Không đề xuất big-bang rewrite**: Chuyển đổi tăng dần theo Strangler Fig (Notification → Reporting → Payroll), giữ nguyên phần code ổn định ít thay đổi.
- [x] **Có test/safety net trước khi tách**: Dựng Characterization Tests (Golden Master) từ dữ liệu kỳ lương lịch sử và Contract Tests trước khi can thiệp.
- [x] **Có rollback và traffic cutover**: Chuyển đổi qua Shadow/Parallel run → Canary (1%–10%–50%–100%) trên Facade/Gateway và Rollback tức thì cả tầng Traffic lẫn Data (Reverse CDC).
- [x] **Có success metric cho migration**: Đo lường bằng độ khớp dữ liệu (`diff = 0`), độ trễ API (< 200ms), tải DB chính (giảm ≥ 40% CPU) và tốc độ phát hành Payroll.

