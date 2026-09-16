Dưới đây là lời giải chi tiết cho bài tập refactor theo nguyên lý **Single Responsibility Principle (SRP)**.

---

### 1. Class `UserService` hiện tại có bao nhiêu trách nhiệm?

Class `UserService` hiện có **6 trách nhiệm** khác nhau:

1. **Xác thực dữ liệu (Validation):** Kiểm tra tính hợp lệ của email.
2. **Mã hóa mật khẩu (Password Hashing):** Xử lý thuật toán mã hóa mật khẩu.
3. **Lưu trữ dữ liệu (Persistence):** Lưu thông tin người dùng vào database.
4. **Gửi thông báo (Notification):** Gửi email chào mừng tới người dùng mới.
5. **Ghi log (Logging):** Ghi thông tin sự kiện ra hệ thống log.
6. **Thống kê / Metric (Analytics/Counter):** Tăng biến đếm tổng số lượng người dùng.

---

### 2. Class này sẽ cần thay đổi trong những trường hợp nào?

`UserService` sẽ cần bị sửa đổi khi xảy ra bất kỳ thay đổi nào trong các trường hợp sau:

* **Thay đổi quy tắc validate:** Ví dụ bổ sung điều kiện độ dài password, định dạng email phức tạp hơn.
* **Thay đổi thuật toán/thư viện mã hóa:** Chuyển đổi từ `MD5/SHA256` sang `BCrypt` hoặc `Argon2`.
* **Thay đổi cơ chế lưu trữ:** Đổi từ JDBC thuần sang Spring Data JPA, Hibernate, hoặc chuyển từ MySQL sang PostgreSQL/MongoDB.
* **Thay đổi dịch vụ email:** Thay đổi template email, provider gửi email (SendGrid, Mailgun) hoặc chuyển sang gửi qua SMS/Push Notification.
* **Thay đổi thư viện hoặc định dạng log:** Đổi framework logging (Log4j sang Logback) hoặc đổi cấu trúc đoạn log.
* **Thay đổi cơ chế thống kê:** Đổi cách đếm đếm số lượng user (sử dụng Redis, Prometheus metrics thay vì đếm thủ công).

---

### 3. Refactor lại thành các class nhỏ hơn

Áp dụng SRP, ta phân tách các trách nhiệm thành các **Interface/Class riêng biệt**, sau đó kết hợp chúng lại ở tầng **UseCase/Service orchestrator**.

#### Các thành phần độc lập (Các trách nhiệm riêng biệt)

```java
// 1. Trách nhiệm Validate Email/User
public class UserValidator {
    public void validate(String email, String password) {
        if (email == null || !email.contains("@")) {
            throw new IllegalArgumentException("Invalid email format");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }
    }
}

// 2. Trách nhiệm Mã hóa Mật khẩu
public interface PasswordEncoder {
    String hash(String rawPassword);
}

// 3. Trách nhiệm Lưu trữ Database
public interface UserRepository {
    void save(User user);
}

// 4. Trách nhiệm Gửi Thông báo
public interface NotificationService {
    void sendWelcomeEmail(String email);
}

// 5. Trách nhiệm Thống kê / Analytics
public interface UserMetricsService {
    void incrementUserCount();
}

```

#### Orchestrator (Điều phối quy trình tạo User)

```java
public class CreateUserUseCase {
    private final UserValidator validator;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final UserMetricsService metricsService;
    private static final Logger logger = LoggerFactory.getLogger(CreateUserUseCase.class);

    public CreateUserUseCase(
        UserValidator validator,
        PasswordEncoder passwordEncoder,
        UserRepository userRepository,
        NotificationService notificationService,
        UserMetricsService metricsService
    ) {
        this.validator = validator;
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.metricsService = metricsService;
    }

    public void execute(String name, String email, String password) {
        // 1. Validate
        validator.validate(email, password);

        // 2. Hash Password
        String hashedPassword = passwordEncoder.hash(password);

        // 3. Save to Database
        User user = new User(name, email, hashedPassword);
        userRepository.save(user);

        // 4. Send Welcome Email
        notificationService.sendWelcomeEmail(email);

        // 5. Log Activity
        logger.info("User created successfully with email: {}", email);

        // 6. Update User Count Metric
        metricsService.incrementUserCount();
    }
}

```

---
