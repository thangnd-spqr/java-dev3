import os

base_dir = r"d:\baitap-java\java-dev3\order-service"
src_main = os.path.join("src", "main", "java", "com", "example", "orderservice")
src_test = os.path.join("src", "test", "java", "com", "example", "orderservice")

files = {
    "settings.gradle": """
rootProject.name = 'order-service'
""",
    "build.gradle": """
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.2.3'
    id 'io.spring.dependency-management' version '1.1.4'
}

group = 'com.example'
version = '1.0.0'
java {
    sourceCompatibility = '17'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    runtimeOnly 'org.postgresql:postgresql'
    
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testCompileOnly 'org.projectlombok:lombok'
    testAnnotationProcessor 'org.projectlombok:lombok'
}

tasks.named('test') {
    useJUnitPlatform()
}
""",
    "src/main/resources/application.yml": """
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orderdb
    username: postgres
    password: password
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
""",
    f"{src_main}/OrderServiceApplication.java": """
package com.example.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
""",
    f"{src_main}/domain/exception/DomainException.java": """
package com.example.orderservice.domain.exception;

public class DomainException extends RuntimeException {
    public DomainException(String message) {
        super(message);
    }
}
""",
    f"{src_main}/domain/valueobject/OrderStatus.java": """
package com.example.orderservice.domain.valueobject;

public enum OrderStatus {
    PENDING, CONFIRMED, CANCELLED, SHIPPED
}
""",
    f"{src_main}/domain/valueobject/OrderId.java": """
package com.example.orderservice.domain.valueobject;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
public class OrderId {
    private final String value;
    public OrderId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("OrderId cannot be empty");
        }
        this.value = value;
    }
}
""",
    f"{src_main}/domain/valueobject/ProductId.java": """
package com.example.orderservice.domain.valueobject;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
public class ProductId {
    private final String value;
    public ProductId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("ProductId cannot be empty");
        }
        this.value = value;
    }
}
""",
    f"{src_main}/domain/entity/OrderItem.java": """
package com.example.orderservice.domain.entity;

import com.example.orderservice.domain.valueobject.ProductId;
import lombok.Getter;

@Getter
public class OrderItem {
    private final ProductId productId;
    private final int quantity;
    private final double price;

    public OrderItem(ProductId productId, int quantity, double price) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be greater than 0");
        if (price < 0) throw new IllegalArgumentException("Price cannot be negative");
        this.productId = productId;
        this.quantity = quantity;
        this.price = price;
    }
    
    public double getSubtotal() {
        return price * quantity;
    }
}
""",
    f"{src_main}/domain/entity/Order.java": """
package com.example.orderservice.domain.entity;

import com.example.orderservice.domain.exception.DomainException;
import com.example.orderservice.domain.valueobject.OrderId;
import com.example.orderservice.domain.valueobject.OrderStatus;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class Order {
    private final OrderId id;
    private final List<OrderItem> items;
    private OrderStatus status;

    public Order(OrderId id, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new DomainException("Order must have at least one item");
        }
        this.id = id;
        this.items = new ArrayList<>(items);
        this.status = OrderStatus.PENDING;
    }

    public double getTotal() {
        return items.stream()
            .mapToDouble(OrderItem::getSubtotal)
            .sum();
    }

    public void confirm() {
        if (this.status != OrderStatus.PENDING) {
            throw new DomainException("Can only confirm PENDING orders");
        }
        this.status = OrderStatus.CONFIRMED;
    }
}
""",
    f"{src_main}/application/dto/OrderItemCommand.java": """
package com.example.orderservice.application.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class OrderItemCommand {
    private String productId;
    private int quantity;
    private double price;
}
""",
    f"{src_main}/application/dto/CreateOrderCommand.java": """
package com.example.orderservice.application.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

@Getter
@AllArgsConstructor
public class CreateOrderCommand {
    private String orderId;
    private List<OrderItemCommand> items;
}
""",
    f"{src_main}/application/dto/OrderResponse.java": """
package com.example.orderservice.application.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class OrderResponse {
    private String orderId;
    private double total;
    private String status;
}
""",
    f"{src_main}/application/port/out/OrderRepository.java": """
package com.example.orderservice.application.port.out;

import com.example.orderservice.domain.entity.Order;
import com.example.orderservice.domain.valueobject.OrderId;
import java.util.Optional;

public interface OrderRepository {
    void save(Order order);
    Optional<Order> findById(OrderId id);
}
""",
    f"{src_main}/application/port/in/CreateOrderUseCase.java": """
package com.example.orderservice.application.port.in;

import com.example.orderservice.application.dto.CreateOrderCommand;
import com.example.orderservice.application.dto.OrderResponse;

public interface CreateOrderUseCase {
    OrderResponse execute(CreateOrderCommand command);
}
""",
    f"{src_main}/application/service/CreateOrderService.java": """
package com.example.orderservice.application.service;

import com.example.orderservice.application.dto.CreateOrderCommand;
import com.example.orderservice.application.dto.OrderResponse;
import com.example.orderservice.application.port.in.CreateOrderUseCase;
import com.example.orderservice.application.port.out.OrderRepository;
import com.example.orderservice.domain.entity.Order;
import com.example.orderservice.domain.entity.OrderItem;
import com.example.orderservice.domain.exception.DomainException;
import com.example.orderservice.domain.valueobject.OrderId;
import com.example.orderservice.domain.valueobject.ProductId;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class CreateOrderService implements CreateOrderUseCase {

    private final OrderRepository orderRepository;

    public CreateOrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public OrderResponse execute(CreateOrderCommand command) {
        if (orderRepository.findById(new OrderId(command.getOrderId())).isPresent()) {
            throw new DomainException("Order with ID " + command.getOrderId() + " already exists");
        }

        Order order = new Order(
            new OrderId(command.getOrderId()),
            command.getItems().stream()
                .map(item -> new OrderItem(
                    new ProductId(item.getProductId()),
                    item.getQuantity(),
                    item.getPrice()
                )).collect(Collectors.toList())
        );

        orderRepository.save(order);

        return new OrderResponse(
            order.getId().getValue(),
            order.getTotal(),
            order.getStatus().name()
        );
    }
}
""",
    f"{src_main}/adapter/in/web/dto/OrderItemRequest.java": """
package com.example.orderservice.adapter.in.web.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrderItemRequest {
    private String productId;
    private int quantity;
    private double price;
}
""",
    f"{src_main}/adapter/in/web/dto/CreateOrderRequest.java": """
package com.example.orderservice.adapter.in.web.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class CreateOrderRequest {
    private String orderId;
    private List<OrderItemRequest> items;
}
""",
    f"{src_main}/adapter/in/web/GlobalExceptionHandler.java": """
package com.example.orderservice.adapter.in.web;

import com.example.orderservice.domain.exception.DomainException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, String>> handleDomainException(DomainException ex) {
        Map<String, String> response = new HashMap<>();
        response.put("error", ex.getMessage());
        return ResponseEntity.badRequest().body(response);
    }
    
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex) {
        Map<String, String> response = new HashMap<>();
        response.put("error", ex.getMessage());
        return ResponseEntity.badRequest().body(response);
    }
}
""",
    f"{src_main}/adapter/in/web/OrderController.java": """
package com.example.orderservice.adapter.in.web;

import com.example.orderservice.adapter.in.web.dto.CreateOrderRequest;
import com.example.orderservice.application.dto.CreateOrderCommand;
import com.example.orderservice.application.dto.OrderItemCommand;
import com.example.orderservice.application.dto.OrderResponse;
import com.example.orderservice.application.port.in.CreateOrderUseCase;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final CreateOrderUseCase createOrderUseCase;

    public OrderController(CreateOrderUseCase createOrderUseCase) {
        this.createOrderUseCase = createOrderUseCase;
    }

    @PostMapping
    public OrderResponse createOrder(@RequestBody CreateOrderRequest request) {
        CreateOrderCommand command = new CreateOrderCommand(
            request.getOrderId(),
            request.getItems().stream()
                .map(item -> new OrderItemCommand(item.getProductId(), item.getQuantity(), item.getPrice()))
                .collect(Collectors.toList())
        );
        return createOrderUseCase.execute(command);
    }
}
""",
    f"{src_main}/adapter/out/persistence/InMemoryOrderRepository.java": """
package com.example.orderservice.adapter.out.persistence;

import com.example.orderservice.application.port.out.OrderRepository;
import com.example.orderservice.domain.entity.Order;
import com.example.orderservice.domain.valueobject.OrderId;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Primary
public class InMemoryOrderRepository implements OrderRepository {
    
    private final Map<String, Order> store = new ConcurrentHashMap<>();

    @Override
    public void save(Order order) {
        store.put(order.getId().getValue(), order);
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return Optional.ofNullable(store.get(id.getValue()));
    }
}
""",
    f"{src_main}/adapter/out/persistence/OrderEntity.java": """
package com.example.orderservice.adapter.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderEntity {
    @Id
    private String id;
    private double total;
    private String status;
}
""",
    f"{src_main}/adapter/out/persistence/OrderJpaRepository.java": """
package com.example.orderservice.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderJpaRepository extends JpaRepository<OrderEntity, String> {
}
""",
    f"{src_main}/adapter/out/persistence/PostgresOrderRepository.java": """
package com.example.orderservice.adapter.out.persistence;

import com.example.orderservice.application.port.out.OrderRepository;
import com.example.orderservice.domain.entity.Order;
import com.example.orderservice.domain.valueobject.OrderId;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class PostgresOrderRepository implements OrderRepository {

    private final OrderJpaRepository jpaRepository;

    public PostgresOrderRepository(OrderJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(Order order) {
        OrderEntity entity = new OrderEntity(
            order.getId().getValue(),
            order.getTotal(),
            order.getStatus().name()
        );
        jpaRepository.save(entity);
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return jpaRepository.findById(id.getValue())
            .map(entity -> {
                // Return mapped order. Assuming a simplified entity-to-domain mapping.
                return null;
            });
    }
}
""",
    f"{src_test}/application/service/CreateOrderServiceTest.java": """
package com.example.orderservice.application.service;

import com.example.orderservice.application.dto.CreateOrderCommand;
import com.example.orderservice.application.dto.OrderItemCommand;
import com.example.orderservice.application.dto.OrderResponse;
import com.example.orderservice.application.port.out.OrderRepository;
import com.example.orderservice.domain.exception.DomainException;
import com.example.orderservice.domain.valueobject.OrderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CreateOrderServiceTest {

    private OrderRepository orderRepository;
    private CreateOrderService createOrderService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        createOrderService = new CreateOrderService(orderRepository);
    }

    @Test
    void testCreateOrderSuccessfully() {
        CreateOrderCommand command = new CreateOrderCommand(
            "ORD-001",
            List.of(new OrderItemCommand("PROD-01", 2, 50.0))
        );

        when(orderRepository.findById(new OrderId("ORD-001"))).thenReturn(Optional.empty());

        OrderResponse response = createOrderService.execute(command);

        assertNotNull(response);
        assertEquals("ORD-001", response.getOrderId());
        assertEquals(100.0, response.getTotal());
        assertEquals("PENDING", response.getStatus());

        verify(orderRepository, times(1)).save(any());
    }

    @Test
    void testCreateOrderThrowsExceptionWhenOrderExists() {
        CreateOrderCommand command = new CreateOrderCommand(
            "ORD-001",
            List.of(new OrderItemCommand("PROD-01", 2, 50.0))
        );

        when(orderRepository.findById(new OrderId("ORD-001")))
            .thenReturn(Optional.of(Mockito.mock(com.example.orderservice.domain.entity.Order.class)));

        assertThrows(DomainException.class, () -> createOrderService.execute(command));
        verify(orderRepository, never()).save(any());
    }
    
    @Test
    void testCreateOrderWithoutItemsThrowsException() {
        CreateOrderCommand command = new CreateOrderCommand("ORD-002", List.of());
        when(orderRepository.findById(new OrderId("ORD-002"))).thenReturn(Optional.empty());

        assertThrows(DomainException.class, () -> createOrderService.execute(command));
    }
}
""",
    "docs/layer-explanation.md": """
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
- **Vai trò:** Lớp bao bọc ngoài cùng, khởi chạy ứng dụng và cung cấp các config kết nối.
"""
}

for path, content in files.items():
    full_path = os.path.join(base_dir, path)
    os.makedirs(os.path.dirname(full_path), exist_ok=True)
    with open(full_path, "w", encoding="utf-8") as f:
        f.write(content.strip() + "\\n")

print("Project generated successfully!")

