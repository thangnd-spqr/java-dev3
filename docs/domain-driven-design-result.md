## **Sản phẩm cần nộp (Kèm giải thích chi tiết)**

- **Domain model**:
  Bạn cần xây dựng và lập trình các thành phần cốt lõi của bài toán bằng mã nguồn (Java), bao gồm:
  - **Entity / Aggregate Root**: `Order` (Quản lý trạng thái và danh sách sản phẩm).
  - **Entity phụ**: `OrderItem` (Chi tiết của từng sản phẩm trong đơn).
  - **Value Object**: `Money` hoặc `Price` (Chứa giá trị tiền tệ và không có định danh độc lập).
  - **Enum**: `OrderStatus` (PENDING, CONFIRMED, DELIVERED, CANCELLED...).
  - **Domain Exceptions**: Các class xử lý ngoại lệ nghiệp vụ khi quy tắc bị vi phạm.
  Mọi hành vi như `addItem()`, `confirm()`, `cancel()` phải được đóng gói bên trong các class này, không đặt logic ở các Controller hay Service bên ngoài.

- **Domain unit tests**:
  Bạn cần viết các Unit Test (ví dụ sử dụng JUnit) để kiểm chứng các hành vi nghiệp vụ (business rules) của Domain Model. 
  - **Case thành công**: Test việc thêm sản phẩm hợp lệ, confirm đơn hàng hợp lệ, tính tổng tiền chính xác.
  - **Case lỗi**: Test các trường hợp vi phạm quy tắc (thêm sản phẩm số lượng âm, confirm đơn hàng rỗng, sửa đơn hàng đã confirm, hủy đơn đã giao...) để đảm bảo hệ thống ném ra đúng các *Domain exceptions*.

- **Sơ đồ aggregate**:
  Một bản vẽ sơ đồ (có thể dùng draw.io, UML, hoặc text diagram) thể hiện cấu trúc của Aggregate:
  - Chỉ ra `Order` là gốc (Root).
  - Mũi tên hoặc quan hệ thể hiện `Order` bao bọc và quản lý vòng đời của các `OrderItem`.
  - Hiển thị các Value Object (`Price`/`Money`, `OrderStatus`) thuộc về các Entity tương ứng.
  - Sơ đồ này chứng minh giới hạn nhất quán (consistency boundary) của hệ thống.

- **Danh sách business rules**:
  Tài liệu (hoặc comment/chú thích) trình bày lại danh sách các quy tắc nghiệp vụ đã được nêu và mô tả ngắn gọn cách bạn đã dùng code để bảo vệ chúng (ví dụ: "Quy tắc: Không thể confirm order rỗng -> Được check trong phương thức `order.confirm()` bằng cách ném Exception nếu danh sách `items` bị trống").

- **Giải thích vì sao `Order` là Aggregate Root**:
  Một đoạn văn bản ngắn gọn giải thích lý thuyết kết hợp với bài toán thực tế:
  - Tại sao bên ngoài không được phép lấy trực tiếp `OrderItem` để sửa đổi mà phải gọi qua `Order`? (Để đảm bảo tính nhất quán dữ liệu, ví dụ khi thêm sửa xóa item thì tổng tiền của Order phải thay đổi theo).
  - `Order` đóng vai trò là điểm giao tiếp duy nhất (entry point) cho toàn bộ cụm đối tượng liên quan đến đơn hàng này.
