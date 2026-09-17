# Giải thích các Layer trong Clean Architecture

## 1. Domain Layer
- **Chứa:** Các Entities (`Order`, `OrderItem`), Value Objects (`OrderId`, `ProductId`), Domain Exceptions (`DomainException`).
- **Vai trò:** Cốt lõi của ứng dụng, chứa business rule thuần túy. 
- **Quy tắc:** Không import bất kỳ Framework nào (kể cả Spring hay JPA), ngoại trừ thư viện sinh code như Lombok. Nó hoàn toàn cô lập.

## 2. Application Layer
- **Chứa:** Ports (`CreateOrderUseCase`, `OrderRepository`), Services (`CreateOrderService`), DTOs.
- **Vai trò:** Điều phối Use Case, gọi đến các Repository Interface.
- **Quy tắc:** Chỉ phụ thuộc vào Domain Layer. Cho phép dùng DI annotion (như `@Service`) nếu không vi phạm sự độc lập của Domain, nhưng không được phép dính dáng tới các HTTP Request/Response hay Logic truy xuất DB cụ thể.

## 3. Adapter Layer
- **Chứa:** 
  - `in`: REST Controllers (`OrderController`), Global Exception Handler, Request DTOs.
  - `out`: Repositories Impl (`InMemoryOrderRepository`, `PostgresOrderRepository`, JPA Entities).
- **Vai trò:** Làm cầu nối giữa thế giới bên ngoài (HTTP, Database) với Application Layer.
- **Quy tắc:** Phụ thuộc vào Application Layer và Domain Layer. Chứa mã nguồn liên quan chặt chẽ tới Web Framework (Spring Web) hoặc Database Framework (Spring Data JPA).

## 4. Infrastructure Layer
- **Chứa:** `OrderServiceApplication`, cấu hình hệ thống (như `application.yml`).
- **Vai trò:** Lớp bao bọc ngoài cùng, khởi chạy ứng dụng và cung cấp các config kết nối.\n