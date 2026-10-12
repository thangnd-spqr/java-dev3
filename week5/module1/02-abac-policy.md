# 02 — ABAC Policy cho HR/Payroll SaaS

## 1. RBAC vs ABAC

| | RBAC | ABAC |
| --- | --- | --- |
| Câu hỏi | User có role/permission X không? | User + resource + môi trường có thoả policy không? |
| Dữ liệu cần | Role, permission của user | Attribute của subject, resource, environment |
| Ưu điểm | Đơn giản, dễ audit | Chi tiết, linh hoạt, tránh role explosion |
| Nhược điểm | Thô, dễ role explosion | Phức tạp hơn, cần load attribute |

**Kết hợp**: RBAC làm *cổng đầu* (có permission không?) → ABAC kiểm tra *ngữ cảnh* (có được làm trên **object này, lúc này** không?).

## 2. Attributes

| Loại | Attribute |
| --- | --- |
| **Subject** (user) | `userId`, `roles`, `permissions`, `tenantId`, `departmentId`, `managedDepartmentIds`, `employeeId` |
| **Resource** | `employee.id`, `employee.tenantId`, `employee.departmentId`, `employee.status`, `payrollRun.tenantId`, `payrollRun.status`, `classification` |
| **Environment** | thời gian hiện tại, IP/network, location, risk score, MFA đã xác thực |

## 3. Policy table

| **Action** | **Điều kiện RBAC** | **Điều kiện ABAC** |
| --- | --- | --- |
| Xem salary | `salary:read` (HR_MANAGER, PAYROLL_ADMIN, AUDITOR) | `user.tenantId == employee.tenantId` **và** (`employee.departmentId ∈ user.managedDepartmentIds` **hoặc** role PAYROLL_ADMIN/AUDITOR); hoặc `employee.id == user.employeeId` (xem lương của chính mình) |
| Sửa salary | `salary:update` (HR_MANAGER+) | cùng tenant; `employee.departmentId ∈ user.managedDepartmentIds`; `employee.status != TERMINATED`; `employee.id != user.employeeId` (không tự sửa lương mình); trong giờ làm việc; MFA = true |
| Chạy payroll | `payroll:run` (PAYROLL_ADMIN+) | `payrollRun.tenantId == user.tenantId`; `payrollRun.status == DRAFT/APPROVED` (chưa chạy); kỳ lương đã đóng; request từ IP công ty; `riskScore < ngưỡng`; người chạy ≠ người tạo/duyệt (4-eyes) |
| Xem audit log | `audit:read` (AUDITOR, SYSTEM_ADMIN) | `log.tenantId == user.tenantId`; chỉ đọc (không có action ghi/xoá); log có `classification = SENSITIVE` chỉ AUDITOR xem; truy cập được ghi lại vào audit log |
| Employee xem profile | `employee:read:self` (EMPLOYEE) | `employee.id == user.employeeId` **và** `employee.tenantId == user.tenantId`; nếu muốn xem người khác → cần `employee:read` + cùng tenant |

## 4. Ví dụ policy dạng code (không hard-code trong controller)

```java
@Component("salaryPolicy")
@RequiredArgsConstructor
public class SalaryPolicy {
    private final EmployeeRepository employees;
    private final Clock clock;

    public boolean canUpdate(Authentication auth, UUID employeeId) {
        var user = (AppUser) auth.getPrincipal();
        var emp  = employees.findById(employeeId).orElse(null);
        if (emp == null || !hasAuthority(auth, "salary:update")) return false;   // RBAC
        return user.tenantId().equals(emp.tenantId())                           // ABAC
            && user.managedDepartmentIds().contains(emp.departmentId())
            && emp.status() != EmployeeStatus.TERMINATED
            && !emp.id().equals(user.employeeId())
            && isBusinessHours(LocalTime.now(clock));
    }

    private boolean hasAuthority(Authentication a, String p) {
        return a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals(p));
    }
    private boolean isBusinessHours(LocalTime t) {
        return !t.isBefore(LocalTime.of(8, 0)) && t.isBefore(LocalTime.of(18, 0));
    }
}
```

```java
@PreAuthorize("@salaryPolicy.canUpdate(authentication, #employeeId)")
public void updateSalary(UUID employeeId, UpdateSalaryRequest req) { ... }
```

## 5. Nguyên tắc

- Policy đặt ở **policy/service layer** (bean riêng), controller chỉ gọi — dễ test, dễ thay đổi, tái sử dụng.
- **Deny by default**: thiếu attribute (null tenant, không tìm thấy employee) → từ chối.
- Attribute lấy từ **nguồn tin cậy** (DB/token đã ký), không lấy `tenantId` từ request body.
- Ghi audit cho mọi quyết định deny với action nhạy cảm (salary, payroll).

