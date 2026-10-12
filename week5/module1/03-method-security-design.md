# 03 — Method Security Design

## 1. Tổng quan các lớp bảo vệ

| API | Request-level | Method-level | Custom PermissionEvaluator | Object-level / ownership |
| --- | --- | --- | --- | --- |
| `GET /employees/{id}` | `authenticated()` | `@PreAuthorize` | ✅ `hasPermission(#id,'Employee','read')` | ✅ self hoặc cùng tenant + department |
| `PUT /employees/{id}/salary` | `hasAuthority('salary:update')` | `@PreAuthorize` | ✅ `hasPermission(#id,'Employee','salary:update')` | ✅ tenant, department, status, không tự sửa |
| `POST /payroll-runs` | `hasAuthority('payroll:run')` | `@PreAuthorize` | ✅ check tenant, IP, giờ | ⚠️ tenant của payroll run |
| `GET /audit/salary/{employeeId}` | `hasAuthority('audit:read')` | `@PreAuthorize` + `@PostAuthorize` | ❌ (SpEL đủ) | ✅ log cùng tenant |

**Quy tắc chia lớp**
- **Request-level** (`SecurityFilterChain`): rule thô, theo URL — authenticated, permission cơ bản. Chặn sớm, rẻ.
- **Method-level** (`@PreAuthorize` ở **service**): rule quan trọng phải nằm ở service vì service có thể được gọi từ nhiều nơi (controller, job, message listener).
- **PermissionEvaluator**: khi cần load object + so sánh attribute (ABAC).
- **Object-level / ACL**: khi quyền phụ thuộc vào *object cụ thể* (owner, tenant, department).

## 2. Request-level config

```java
http.authorizeHttpRequests(a -> a
    .requestMatchers(HttpMethod.PUT,  "/employees/*/salary").hasAuthority("salary:update")
    .requestMatchers(HttpMethod.POST, "/payroll-runs").hasAuthority("payroll:run")
    .requestMatchers("/audit/**").hasAuthority("audit:read")
    .anyRequest().authenticated());
```

## 3. Method-level (service)

```java
@Service
public class EmployeeService {
    @PreAuthorize("hasPermission(#id, 'Employee', 'read')")
    public EmployeeResponse get(UUID id) { ... }

    @PreAuthorize("hasAuthority('salary:update') and hasPermission(#id, 'Employee', 'salary:update')")
    public void updateSalary(UUID id, UpdateSalaryRequest req) { ... }
}

@Service
public class PayrollService {
    @PreAuthorize("hasAuthority('payroll:run') and @payrollPolicy.canRun(authentication, #req)")
    public PayrollRun run(CreatePayrollRunRequest req) { ... }
}

@Service
public class AuditService {
    @PreAuthorize("hasAuthority('audit:read')")
    @PostAuthorize("returnObject.tenantId == authentication.principal.tenantId")
    public SalaryAuditResponse getSalaryAudit(UUID employeeId) { ... }
}
```

## 4. Custom PermissionEvaluator

```java
@Component
@RequiredArgsConstructor
public class EmployeePermissionEvaluator implements PermissionEvaluator {
    private final EmployeeRepository repo;

    @Override
    public boolean hasPermission(Authentication auth, Serializable id, String type, Object perm) {
        if (!"Employee".equals(type)) return false;
        var user = (AppUser) auth.getPrincipal();
        var emp  = repo.findById((UUID) id).orElse(null);
        if (emp == null || !emp.tenantId().equals(user.tenantId())) return false;   // tenant isolation
        return switch (perm.toString()) {
            case "read" -> emp.id().equals(user.employeeId())                       // ownership
                        || user.managedDepartmentIds().contains(emp.departmentId());
            case "salary:update" -> user.managedDepartmentIds().contains(emp.departmentId())
                        && emp.status() != EmployeeStatus.TERMINATED
                        && !emp.id().equals(user.employeeId());
            default -> false;                                                       // deny by default
        };
    }

    @Override
    public boolean hasPermission(Authentication a, Object target, Object perm) { return false; }
}
```

## 5. Test cases allow / deny

| API | Case | Kết quả |
| --- | --- | --- |
| `GET /employees/{id}` | EMPLOYEE xem profile chính mình | ✅ 200 |
| | EMPLOYEE xem profile người khác | ❌ 403 |
| | HR_MANAGER xem nhân viên department mình quản lý | ✅ 200 |
| | HR_MANAGER xem nhân viên tenant khác | ❌ 403 |
| | Chưa đăng nhập | ❌ 401 |
| `PUT /employees/{id}/salary` | HR_MANAGER, cùng tenant + department | ✅ 200 |
| | HR_SPECIALIST (không có `salary:update`) | ❌ 403 |
| | HR_MANAGER sửa nhân viên department khác | ❌ 403 |
| | HR_MANAGER sửa nhân viên `TERMINATED` | ❌ 403 |
| | HR_MANAGER tự sửa lương mình | ❌ 403 |
| `POST /payroll-runs` | PAYROLL_ADMIN, cùng tenant, IP công ty | ✅ 201 |
| | HR_MANAGER (không có `payroll:run`) | ❌ 403 |
| | PAYROLL_ADMIN chạy payroll của tenant khác | ❌ 403 |
| `GET /audit/salary/{employeeId}` | AUDITOR cùng tenant | ✅ 200 |
| | HR_MANAGER (không có `audit:read`) | ❌ 403 |
| | AUDITOR xem log tenant khác | ❌ 403 |

```java
@Test @WithMockUser(authorities = {"employee:read", "employee:update"})   // HR_SPECIALIST: không có salary:update
void hrSpecialistCannotUpdateSalary() {
    assertThrows(AccessDeniedException.class, () -> employeeService.updateSalary(empId, req));
}
```

## 6. Lưu ý quan trọng

- `@PreAuthorize` **chỉ bảo vệ method có annotation**, và chỉ khi gọi qua Spring proxy (gọi `this.method()` nội bộ sẽ **bỏ qua** check). Method mới thêm không annotation = không được bảo vệ → nên có test/ArchUnit kiểm tra.
- Cần `@EnableMethodSecurity` để kích hoạt.
- **Role check** ≠ **ownership check**: `hasAuthority('salary:update')` chỉ nói *được sửa lương*, không nói *được sửa lương của ai* → phải kết hợp object-level check.
- Không chỉ dựa vào controller/URL security: rule nghiệp vụ quan trọng đặt ở service.
