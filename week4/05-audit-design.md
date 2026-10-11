# 05 - Audit Design: EmployeeSalary (Hibernate Envers)

## 1. Audit trail vs Application log
| | Audit trail | Application log |
|---|---|---|
| Mục đích | Bằng chứng nghiệp vụ: ai, sửa gì, khi nào, trước/sau | Debug, vận hành |
| Lưu ở | DB (audit table), transactional với dữ liệu | File/ELK, có thể mất/rotate |
| Độ tin cậy | Bắt buộc đầy đủ, không sửa/xóa tùy ý | Best-effort |
| Retention | Theo luật/chính sách (vd 7 năm) | Ngắn (vd 14–30 ngày) |

## 2. Entity
```java
@Entity @Audited
public class EmployeeSalary {
    @Id Long id;
    String employeeId;
    BigDecimal baseSalary;
    LocalDate effectiveDate;
    @Enumerated(STRING) SalaryStatus status;
    @Version Long version;                    // dùng cho locking
    @NotAudited String internalNote;          // không cần lịch sử
}

@Entity @RevisionEntity(AuditRevisionListener.class)
public class AuditRevision extends DefaultRevisionEntity {
    String actor; String actorRole; String correlationId; String clientIp; String reason;
}
// AuditRevisionListener: lấy actor từ SecurityContext, correlationId từ MDC
```
Envers ghi audit khi transaction **commit**; rollback → không có revision.

## 3. Audit schema tối giản
**`revinfo`** (1 dòng / transaction)
| Cột | Kiểu | Ghi chú |
|---|---|---|
| rev | BIGINT PK | revision id |
| revtstmp | TIMESTAMP | thời điểm commit |
| actor | VARCHAR(100) | username / userId người sửa |
| actor_role | VARCHAR(50) | HR_MANAGER, ADMIN, SYSTEM_BATCH |
| correlation_id | VARCHAR(64) | request/trace id (header `X-Correlation-Id`) |
| client_ip | VARCHAR(45) | tùy chính sách |
| reason | VARCHAR(255) | lý do điều chỉnh (optional) |

**`employee_salary_aud`** (1 dòng / entity / revision)
| Cột | Kiểu | Ghi chú |
|---|---|---|
| id | BIGINT | PK entity |
| rev | BIGINT FK → revinfo | PK(id, rev) |
| revtype | TINYINT | 0=ADD, 1=MOD, 2=DEL |
| revend | BIGINT | (ValidityAuditStrategy) rev kết thúc hiệu lực |
| employee_id | VARCHAR | |
| base_salary | DECIMAL(15,2) | |
| effective_date | DATE | |
| status | VARCHAR(20) | |

Index: `(employee_id, rev)`, `(rev)`, `revinfo(correlation_id)`, `revinfo(revtstmp)`.

## 4. Đáp ứng yêu cầu
- **Ai sửa lương** → `revinfo.actor`, `actor_role`.
- **Giá trị trước/sau** → so sánh 2 revision liên tiếp của cùng `id`.
- **Request nào gây thay đổi** → `revinfo.correlation_id` (nối với log/trace).
- **Salary tại thời điểm bất kỳ** → `AuditReader.find(EmployeeSalary.class, id, date)` hoặc
  `forRevisionsOfEntity(...)`. Dùng **ValidityAuditStrategy** (có `revend`) để query point-in-time nhanh hơn (đổi lại write cost cao hơn).

## 5. Quy định dữ liệu audit / không audit
| Audit | Không audit |
|---|---|
| base_salary, effective_date, status, employee_id | password, token, OTP, secret |
| actor, role, correlation_id, timestamp | số tài khoản ngân hàng đầy đủ, CCCD, địa chỉ nhà (nếu không cần) |
| lý do điều chỉnh | ghi chú nội bộ, field kỹ thuật (cache, lastViewedAt) |
- Dùng `@Audited` ở mức entity cần thiết, `@NotAudited` cho field thừa; entity User/Credential **không** `@Audited`.
- Salary là dữ liệu nhạy cảm → audit table phân quyền chặt (chỉ HR/Admin/Auditor), mã hóa at-rest, truy cập audit cũng được log.

## 6. Retention & privacy
- Giữ audit salary theo luật lao động/thuế (vd **7–10 năm**), sau đó archive (cold storage) rồi purge.
- Partition `employee_salary_aud`/`revinfo` theo tháng/năm để archive/xóa nhanh.
- Audit table **append-only**: không cho update/delete từ app; yêu cầu xóa (privacy) → anonymize actor/PII theo quy trình được duyệt.

## 7. Chi phí & performance
- Mỗi update = thêm 1 insert `revinfo` + 1 insert `_aud` (+ update `revend`) → **tăng storage & write latency**.
- Batch update hàng triệu dòng → audit table phình nhanh; cân nhắc audit theo batch-level hoặc tắt Envers cho bảng tạm.
- Query lịch sử: luôn lọc theo `id/employee_id` + khoảng thời gian, phân trang.

## 8. Hiển thị lịch sử cho HR/Admin
Màn hình *Lịch sử lương* của nhân viên E-100 (mới nhất trước, phân trang, lọc theo khoảng ngày/actor):

| Thời điểm | Người sửa | Field | Trước | Sau | Request ID | Lý do |
|---|---|---|---|---|---|---|
| 2026-10-10 09:12 | managerA (HR_MANAGER) | base_salary | 20,000,000 | 25,000,000 | c-7f3a… | Tăng lương định kỳ |
| 2026-09-01 08:00 | SYSTEM_BATCH | status | DRAFT | ACTIVE | batch-0925 | Import CSV |

- Chức năng **"Xem lương tại ngày X"** → snapshot point-in-time.
- Click Request ID → mở trace/log liên quan. Export CSV cho auditor (có watermark, log hành động export).

