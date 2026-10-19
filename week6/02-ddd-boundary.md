# 02 — DDD Boundary cho HR/Payroll SaaS

## 1. Domain & Subdomain

| Subdomain | Loại | Lý do |
| --- | --- | --- |
| Payroll | **Core** | Tạo khác biệt cạnh tranh: tính lương đúng, đúng luật thuế, nhiều tenant |
| Leave | Supporting | Cần thiết, có nghiệp vụ riêng nhưng không phải lợi thế chính |
| Employee | Supporting | Nguồn dữ liệu nhân sự gốc cho các context khác |
| Identity | Generic | Bài toán chung, có thể mua/dùng IdP (Keycloak, Cognito) |
| Notification | Generic | Gửi email/push, không có nghiệp vụ đặc thù |

> Chia service theo **business capability**, không theo technical layer (`UserController`, `EmployeeEntity`, `common-dao`...).

## 2. Bounded Contexts & Ubiquitous Language

| Context | Ubiquitous Language |
| --- | --- |
| **Employee** | Employee, Department, Position, Employment Status (Probation/Active/Terminated), Hire Date, Manager, Contract |
| **Payroll** | Payroll Run, Payroll Period, Payslip, Gross/Net Salary, Salary Grade, Allowance, Deduction, Salary Adjustment, Tax Profile, Tax Calculation, Lock/Finalize |
| **Leave** | Leave Request, Leave Type, Leave Balance, Leave Policy, Accrual, Approval, Approver Chain, Cancel/Reject |
| **Identity** | User Account, Tenant, Credential, Authentication, Role, Permission, Session/Token |

Mỗi context có **model riêng**, **DB/schema riêng**, và ngôn ngữ thống nhất giữa dev + business **bên trong** context đó.

## 3. Cùng một từ — khác nghĩa theo context

| Từ | Employee Context | Payroll Context | Leave Context | Identity Context |
| --- | --- | --- | --- | --- |
| **Employee** | Hồ sơ nhân sự, phòng ban, trạng thái làm việc | `employeeId`, tax profile, salary grade, bank account | `employeeId`, leave balance, policy, approver chain | Không có — chỉ có `UserAccount` (liên kết qua `employeeId`) |
| **Manager** | Quan hệ báo cáo trong sơ đồ tổ chức | Không quan tâm | Người **approve** đơn nghỉ | Một **role** có quyền truy cập |
| **Period** | — | Tháng chốt lương (`2026-10`) | Năm tính phép (cấp phép theo năm) | — |
| **Active** | Đang làm việc | Payroll run đang tính, chưa finalize | Đơn chưa kết thúc | Tài khoản chưa bị khoá |

> Một class `Employee` khổng lồ (hồ sơ + lương + phép + mật khẩu) dùng chung toàn hệ thống
> là dấu hiệu boundary chưa rõ → mỗi thay đổi đều ảnh hưởng mọi team.

## 4. Aggregate, Aggregate Root & Invariant

**Aggregate** = cụm object thay đổi cùng nhau trong **một transaction**; mọi thay đổi phải đi qua **Aggregate Root**
để root kiểm tra **invariant** (luật nghiệp vụ luôn đúng). Bên ngoài chỉ tham chiếu aggregate khác bằng **ID**.

| Context | Aggregate Root | Thành phần bên trong | Invariant | Data Owner |
| --- | --- | --- | --- | --- |
| Employee | `Employee` | Contract, EmploymentHistory | Employee `TERMINATED` không thể chuyển phòng ban; mỗi employee chỉ có 1 contract hiệu lực tại một thời điểm | Employee Service |
| Employee | `Department` | — | Mã phòng ban là duy nhất trong tenant; không xoá phòng ban còn nhân viên active | Employee Service |
| Payroll | `PayrollRun` | Payslip, PayslipLine | Một tenant chỉ có **một** payroll run active cho cùng tháng; run đã `FINALIZED` không được sửa payslip | Payroll Service |
| Payroll | `SalaryProfile` | SalaryAdjustment, TaxProfile | Net salary ≥ 0; adjustment có hiệu lực không chồng lấp thời gian | Payroll Service |
| Leave | `LeaveRequest` | ApprovalStep | Không được approve request đã `CANCELLED`/`REJECTED`; `endDate ≥ startDate`; phải duyệt đúng thứ tự approver chain | Leave Service |
| Leave | `LeaveBalance` | LeaveTransaction | Số ngày còn lại không âm (trừ khi policy cho phép); 1 balance / employee / leave type / năm | Leave Service |
| Identity | `UserAccount` | Credential, RoleAssignment | Username/email duy nhất trong tenant; tài khoản `LOCKED` không thể đăng nhập | Identity Service |

### Ví dụ: aggregate bảo vệ invariant

```java
public class PayrollRun {                       // Aggregate Root
    private PayrollStatus status;
    private final List<Payslip> payslips = new ArrayList<>();

    public void addAdjustment(EmployeeId empId, Money amount) {
        if (status == PayrollStatus.FINALIZED)
            throw new DomainException("Payroll run đã finalize, không thể sửa");
        findPayslip(empId).addLine(PayslipLine.adjustment(amount)); // không cho sửa Payslip trực tiếp từ ngoài
    }

    public PayrollFinalized finalizeRun() {
        if (status != PayrollStatus.CALCULATED) throw new DomainException("Chưa tính xong lương");
        status = PayrollStatus.FINALIZED;
        return new PayrollFinalized(id, tenantId, period);   // domain event
    }
}
```

Invariant "một run active / tenant / tháng" vượt ra ngoài 1 aggregate → enforce thêm bằng
**unique constraint** `(tenant_id, period) WHERE status <> 'CANCELLED'` trong DB của Payroll Service.

## 5. Data Ownership

| Dữ liệu | Owner (duy nhất được ghi) | Context khác dùng thế nào |
| --- | --- | --- |
| `employees`, `departments`, `contracts` | Employee Service | Payroll/Leave nhận event `EmployeeHired`, `EmployeeTerminated` → lưu bản sao tối thiểu |
| `payroll_runs`, `payslips`, `salary_profiles`, `tax_profiles` | Payroll Service | Reporting đọc qua event/read model; nhân viên xem payslip qua API Payroll |
| `leave_requests`, `leave_balances`, `leave_policies` | Leave Service | Payroll nhận `LeaveApproved` (unpaid leave) để tính khấu trừ |
| `user_accounts`, `roles`, `credentials` | Identity Service | Service khác chỉ đọc claim trong JWT (`userId`, `tenantId`, `roles`) |

Quy tắc:
- **Một bảng chỉ có một service ghi.** Không share DB, không join chéo schema.
- Context khác cần dữ liệu → gọi **API** hoặc subscribe **domain event** và giữ **local copy** (read-only, eventual consistency).
- Ví dụ: Payroll lưu `employee_snapshot(employee_id, salary_grade, bank_account)` — không đọc bảng `employees`.

## 6. Domain Events giữa các context

```text
Employee  --EmployeeHired-------->  Payroll (tạo SalaryProfile), Leave (khởi tạo LeaveBalance), Identity (tạo UserAccount)
Employee  --EmployeeTerminated--->  Payroll (payslip cuối), Leave (đóng balance), Identity (khoá tài khoản)
Leave     --LeaveApproved-------->  Payroll (khấu trừ nếu unpaid leave)
Payroll   --PayrollFinalized----->  Notification (gửi payslip), Reporting (cập nhật dashboard)
```

Event được publish qua **transactional outbox** → đảm bảo không mất event khi DB commit thành công.

## Checklist

- [x] Không chia service theo controller/entity technical layer — chia theo business capability.
- [x] Biết aggregate bảo vệ invariant — mọi thay đổi đi qua Aggregate Root.
- [x] Không để nhiều service cùng ghi một bảng — mỗi bảng có một data owner duy nhất.
- [x] Biết từ giống nhau có thể khác nghĩa ở context khác nhau (`Employee`, `Manager`, `Period`, `Active`).

