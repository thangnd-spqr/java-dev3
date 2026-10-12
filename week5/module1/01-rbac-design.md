# 01 — RBAC Design cho HR/Payroll SaaS

## 1. Role vs Permission

| Khái niệm | Ý nghĩa | Ví dụ |
| --- | --- | --- |
| **Permission** | Quyền thực hiện 1 action trên 1 resource (nhỏ, ổn định) | `salary:update` |
| **Role** | Nhóm permission theo chức danh/nghiệp vụ | `ROLE_HR_MANAGER` |
| **Authority** | Thứ Spring nhận (`GrantedAuthority`) — gồm cả role và permission | `ROLE_HR_MANAGER`, `salary:read` |

Mô hình: `User → Role(s) → Permission(s)`. Code **check permission**, không check role → đổi mapping role không phải sửa code.

## 2. Permissions

| Permission | Mô tả |
| --- | --- |
| `employee:read:self` / `employee:update:self` | Xem / sửa profile của chính mình |
| `employee:read` / `employee:update` | Xem / sửa profile nhân viên khác |
| `salary:read` / `salary:update` | Xem / sửa lương |
| `payroll:read` / `payroll:run` / `payroll:approve` | Xem / chạy / duyệt bảng lương |
| `audit:read` | Xem audit log |
| `tenant:manage` / `user:manage` | Cấu hình tenant, quản lý user & role |

## 3. Roles → Permissions

| Role | Permissions (riêng, chưa tính kế thừa) |
| --- | --- |
| `EMPLOYEE` | `employee:read:self`, `employee:update:self` |
| `HR_SPECIALIST` | `employee:read`, `employee:update` |
| `HR_MANAGER` | `salary:read`, `salary:update` |
| `PAYROLL_ADMIN` | `payroll:read`, `payroll:run`, `payroll:approve` |
| `AUDITOR` | `audit:read`, `salary:read`, `payroll:read` (**read-only**) |
| `SYSTEM_ADMIN` | `tenant:manage`, `user:manage` |

## 4. Role hierarchy

```
ROLE_SYSTEM_ADMIN
    ↓
ROLE_PAYROLL_ADMIN
    ↓
ROLE_HR_MANAGER
    ↓
ROLE_HR_SPECIALIST
    ↓
ROLE_EMPLOYEE

ROLE_AUDITOR → ROLE_EMPLOYEE   (nhánh riêng, KHÔNG kế thừa quyền ghi)
```

```java
@Bean
static RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.fromHierarchy("""
        ROLE_SYSTEM_ADMIN > ROLE_PAYROLL_ADMIN
        ROLE_PAYROLL_ADMIN > ROLE_HR_MANAGER
        ROLE_HR_MANAGER > ROLE_HR_SPECIALIST
        ROLE_HR_SPECIALIST > ROLE_EMPLOYEE
        ROLE_AUDITOR > ROLE_EMPLOYEE
        """);
}
```

> Lưu ý: `AUDITOR` tách nhánh để đảm bảo **separation of duties** — người kiểm toán không được sửa dữ liệu mình kiểm toán.
> Có thể cân nhắc `SYSTEM_ADMIN` **không** kế thừa quyền xem lương (chỉ quản trị kỹ thuật) nếu policy công ty yêu cầu.

## 5. Vì sao không dùng `ROLE_ADMIN` cho mọi action

- **Vi phạm least privilege**: ai cần chạy payroll cũng thấy được audit log, quản lý user…
- **Không có separation of duties**: 1 người vừa sửa lương, vừa chạy payroll, vừa xem/xoá dấu vết audit → rủi ro gian lận.
- **Blast radius lớn**: lộ 1 tài khoản admin = lộ toàn hệ thống.
- **Audit vô nghĩa**: log chỉ ghi "ADMIN đã làm", không biết hành động có hợp lệ theo nghiệp vụ không.
- **Khó thay đổi**: muốn bớt 1 quyền phải tạo role mới + sửa code check role.

## 6. 3 rủi ro của "Role explosion"

1. **Khó quản lý & review quyền**: hàng trăm role kiểu `HR_MANAGER_HN_IT`, `HR_MANAGER_HCM_SALES`… → không ai biết role nào có quyền gì, access review thất bại.
2. **Gán sai / dư quyền**: admin chọn nhầm role gần giống, user tích luỹ nhiều role theo thời gian (privilege creep).
3. **Code & config phình to, dễ lỗi**: mỗi điều kiện business (tenant, department, location) thành 1 role → `@PreAuthorize` dài, nhiều chỗ check không đồng nhất, dễ sót.

## 7. Khi RBAC không đủ chi tiết

RBAC chỉ trả lời *"user có quyền `salary:update` không?"*, không trả lời được:

- HR_MANAGER chỉ được sửa lương **nhân viên thuộc department mình quản lý**.
- Chỉ trong **cùng tenant** (multi-tenant SaaS).
- Không sửa lương nhân viên có status `TERMINATED`, không sửa **lương của chính mình**.
- Chỉ chạy payroll **trong giờ hành chính / từ IP công ty**.

→ Những rule này dùng **ABAC** (attribute của subject/resource/environment), kết hợp với RBAC — xem `02-abac-policy.md`.

