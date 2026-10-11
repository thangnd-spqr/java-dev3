# 04 - Locking Strategy: Salary của Employee E-100

## Bối cảnh
Hai HR manager cùng sửa salary E-100 (đang 20M): A → 25M, B → 22M.

## 1. Lost update nếu không lock
| Thời điểm | Manager A | Manager B | DB |
|---|---|---|---|
| t1 | đọc salary = 20M | | 20M |
| t2 | | đọc salary = 20M | 20M |
| t3 | `UPDATE ... = 25M` commit | | 25M |
| t4 | | `UPDATE ... = 22M` commit | **22M** |

→ Thay đổi của A **bị ghi đè âm thầm**; B không hề biết A đã sửa. Không lỗi, không cảnh báo.

## 2. Thiết kế với `@Version` (Optimistic Lock)
```java
@Entity
public class EmployeeSalary {
    @Id private Long id;
    private String employeeId;
    private BigDecimal baseSalary;
    @Version private Long version;   // tăng mỗi lần update
}
```
- Hibernate sinh: `UPDATE employee_salary SET base_salary=?, version=version+1 WHERE id=? AND version=?`.
- API: GET trả `version` (hoặc `ETag`), PUT gửi kèm `version` (hoặc `If-Match`).
- Kịch bản: A và B cùng đọc `version=1`. A commit → `version=2`. B update `WHERE version=1` → **0 row** → exception.

## 3. Exception & UX khi conflict
- Hibernate ném `OptimisticLockException` / Spring `ObjectOptimisticLockingFailureException`.
- `@ControllerAdvice` map → **HTTP 409 Conflict** (hoặc 412 nếu dùng `If-Match`):
  ```json
  { "code": "SALARY_CONFLICT",
    "message": "Salary đã được người khác cập nhật. Vui lòng tải lại.",
    "currentValue": 25000000, "currentVersion": 2, "updatedBy": "managerA" }
  ```
- UX: hiển thị giá trị hiện tại vs giá trị user nhập → user chọn **Reload** / **Ghi đè có xác nhận** (gửi lại với version mới).
- **Không auto-retry** cho thao tác nghiệp vụ do người dùng nhập (salary) — phải để người quyết định. Auto-retry chỉ cho update kiểu cộng dồn/idempotent.

## 4. Khi nào cân nhắc Pessimistic Lock
- Conflict **cao**, retry tốn kém: vd trừ quota/ngân sách lương phòng ban, reserve slot khan hiếm, sinh số thứ tự.
- Transaction **ngắn**, chỉ thao tác DB, không có user/IO ngoài trong lúc giữ lock.
- Dùng: `@Lock(LockModeType.PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE`, kèm hint
  `jakarta.persistence.lock.timeout` (vd 3000ms) để tránh chờ vô hạn.
- Rủi ro: **lock wait/timeout**, **deadlock** (2 transaction khóa ngược thứ tự) → luôn khóa theo thứ tự cố định (vd theo id tăng dần), giữ transaction nhỏ.
- Pessimistic lock **không phải "an toàn hơn nên dùng mọi nơi"**: giảm throughput, giữ connection, dễ cascade timeout.

## 5. Vì sao không giữ pessimistic lock khi gọi external Tax Service
- Latency external **không kiểm soát** (giây → timeout) → row bị khóa suốt thời gian đó, request khác xếp hàng.
- Giữ **DB connection + transaction** lâu → cạn connection pool, ảnh hưởng toàn hệ thống.
- Tax Service lỗi/retry → lock càng lâu, tăng nguy cơ deadlock và lock timeout dây chuyền.
- Rollback DB không rollback được side effect bên ngoài → không nhất quán.

**Cách đúng:**
1. Gọi Tax Service **trước/ngoài transaction** để tính thuế.
2. Mở transaction ngắn: load entity + kiểm tra `@Version` → update → commit.
3. Nếu conflict → reload, tính lại nếu cần. Với flow dài dùng Saga/Outbox.

## Tóm tắt
| | Optimistic (`@Version`) | Pessimistic (`FOR UPDATE`) |
|---|---|---|
| Dùng cho | Sửa salary qua UI (conflict thấp) | Tài nguyên khan hiếm, conflict cao |
| Khi conflict | 409 + reload/merge | Chờ / lock timeout |
| Rủi ro | User phải làm lại | Deadlock, cạn pool |

**Quyết định:** dùng **Optimistic Lock** cho cập nhật salary E-100.

