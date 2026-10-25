# HTTP Contract: Order Service ↔ Product Service (`GET /products/{id}`)

## 1. Tổng quan kiến trúc Spring Cloud Contract

Trước khi đi vào chi tiết bản hợp đồng (Contract), dưới đây là các thành phần cốt lõi trong hệ sinh thái **Spring Cloud Contract**:

| Khái niệm | Giải thích vai trò trong thực tế |
| :--- | :--- |
| **Producer Contract** | Hợp đồng được lưu trữ và quản lý tại phía service cung cấp dữ liệu (**Product Service**). Mọi thay đổi trên API của Producer đều được kiểm chứng trực tiếp với bộ contract này. |
| **Consumer Contract** | Yêu cầu/kỳ vọng từ phía service gọi (**Order Service**) về cấu trúc request/response mà nó cần. Trong mô hình **Consumer-Driven Contracts (CDC)**, Consumer đề xuất contract (qua PR vào repo của Producer hoặc repo contract chung) để đảm bảo Producer biết rõ Consumer đang dùng những field nào. |
| **Stub** | Bản giả lập (WireMock mapping cho HTTP hoặc message definition cho Messaging) được **sinh tự động** từ file Contract sau khi đã qua kiểm chứng ở Producer. Giúp Consumer tránh lỗi "mock tự bịa bị lệch thực tế". |
| **Provider Verification** | Quá trình `spring-cloud-contract-maven-plugin` đọc các file Contract và **tự động sinh ra JUnit test** (`ContractVerifierTest`) bắn request thật vào Controller của Producer (qua `MockMvc` / `RestAssured`). Nếu code Producer trả sai cấu trúc, build lập tức fail. |
| **Contract Artifact** | File JAR đặc biệt có classifier là `stubs` (ví dụ: `product-service-1.2.0-stubs.jar`) chứa toàn bộ các file contract và WireMock stubs, được publish lên Artifact Repository (Nexus / Artifactory / GitHub Packages) để Consumer tải về dùng. |
| **Contract Version** | Phiên bản của Contract Artifact (thường quản lý theo Semantic Versioning `MAJOR.MINOR.PATCH`). Consumer có thể chỉ định bản cố định (`1.2.0`) hoặc bản mới nhất (`+`) trong `@AutoConfigureStubRunner` để kiểm tra tính tương thích. |
| **HTTP Contract** | Định nghĩa giao thức đồng bộ (Request: method, URL, headers, query/body $\rightarrow$ Response: status code, headers, body JSON). |
| **Messaging Contract** | Định nghĩa giao thức bất đồng bộ qua Broker (Kafka/RabbitMQ): sự kiện kích hoạt (`input`), destination/topic (`sentTo`), headers và message payload (`body`). |

---

## 2. Đặc tả HTTP Contract (`GET /products/{id}`)

### Bối cảnh nghiệp vụ
Khi khách hàng thực hiện **Checkout**, **Order Service (Consumer)** gọi sang **Product Service (Producer)** qua endpoint `GET /products/{id}` để kiểm tra:
1. Sản phẩm có tồn tại và đang mở bán hay không (`available == true`).
2. Giá bán hiện tại (`price`) để tính tổng tiền đơn hàng.
3. Tên hiển thị của sản phẩm (`name`) để lưu vào snapshot của Order Item.

### 2.1. Kịch bản 1: Lấy thông tin sản phẩm thành công (`200 OK`)

#### Định dạng YAML DSL (`src/test/resources/contracts/order-consumer/shouldReturnProductWhenIdExists.yml`)

```yaml
description: |
  Trả về thông tin chi tiết sản phẩm khi ID hợp lệ để Order Service thực hiện Checkout.
name: should_return_product_when_id_exists
request:
  method: GET
  url: /products/P-100
  headers:
    Accept: application/json
  matchers:
    url:
      # Cho phép Consumer gửi bất kỳ mã sản phẩm hợp lệ nào dạng P-xxx khi chạy với Stub
      regex: '/products/P-[0-9]{3,6}'
response:
  status: 200
  headers:
    Content-Type: application/json
  body:
    id: "P-100"
    name: "Keyboard"
    price: 120.50
    available: true
  matchers:
    body:
      - path: $.id
        type: by_regex
        value: "^P-[0-9]{3,6}$"
      - path: $.name
        type: by_regex
        value: "^.+$"
      - path: $.price
        type: by_regex
        value: "^[0-9]+(\\.[0-9]{1,2})?$"
      - path: $.available
        type: by_regex
        value: "^(true|false)$"
```

#### Định dạng Groovy DSL (`src/test/resources/contracts/order-consumer/shouldReturnProductWhenIdExists.groovy`)

```groovy
package contracts.order_consumer

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description("Trả về thông tin sản phẩm P-100 cho Order Service khi kiểm tra tồn kho và giá")
    name("should_return_product_when_id_exists")

    request {
        method GET()
        url value(
            consumer(regex('/products/P-[0-9]{3,6}')),
            producer('/products/P-100')
        )
        headers {
            accept(applicationJson())
        }
    }

    response {
        status OK()
        headers {
            contentType(applicationJson())
        }
        body([
            id       : value(consumer(fromRequest().path(1)), producer("P-100")),
            name     : value(consumer("Keyboard"), producer(regex('.+'))),
            price    : value(consumer(120.50), producer(regex('[0-9]+(\\.[0-9]{1,2})?'))),
            available: value(consumer(true), producer(anyBoolean()))
        ])
    }
}
```

> **Giải thích cơ chế Dual-Value (`consumer(...)` vs `producer(...)`)**:
> - **Phía Consumer (Stub)**: Khi Order Service gọi `GET /products/P-100` vào WireMock Stub, Stub trả về chính xác `{ "id": "P-100", "name": "Keyboard", "price": 120.50, "available": true }`.
> - **Phía Producer (Verification)**: Khi Spring Cloud Contract tự sinh test cho Product Service, nó bắn request `GET /products/P-100` và kiểm tra:
>   - `$.id` khớp `"P-100"`
>   - `$.name` là chuỗi không rỗng
>   - `$.price` là kiểu số hợp lệ
>   - `$.available` bắt buộc tồn tại và là kiểu boolean (`true` hoặc `false`).

---

### 2.2. Kịch bản 2: Sản phẩm không tồn tại (`404 Not Found` - Error Contract)

Consumer thường rẽ nhánh xử lý dựa trên Error Response. Do đó, Error Contract cũng phải được chuẩn hóa.

```yaml
description: |
  Trả về lỗi 404 chuẩn khi không tìm thấy sản phẩm.
name: should_return_404_when_product_not_found
request:
  method: GET
  url: /products/P-999999
  headers:
    Accept: application/json
response:
  status: 404
  headers:
    Content-Type: application/json
  body:
    errorCode: "PRODUCT_NOT_FOUND"
    message: "Product with id P-999999 was not found"
  matchers:
    body:
      - path: $.errorCode
        type: by_equality
      - path: $.message
        type: by_regex
        value: "^.+$"
```

---

## 3. Triển khai phía Producer (Product Service)

### 3.1. Cấu hình `pom.xml` trên Product Service

```xml
<plugin>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-contract-maven-plugin</artifactId>
    <version>4.1.2</version>
    <extensions>true</extensions>
    <configuration>
        <testFramework>JUNIT5</testFramework>
        <baseClassForTests>
            com.example.product.contract.BaseProductContractTest
        </baseClassForTests>
    </configuration>
</plugin>
```

### 3.2. Base Test Class (`BaseProductContractTest.java`)

Plugin sẽ tạo class test kế thừa từ class này để thực thi **Provider Verification**:

```java
package com.example.product.contract;

import com.example.product.controller.ProductController;
import com.example.product.dto.ProductResponse;
import com.example.product.exception.GlobalExceptionHandler;
import com.example.product.exception.ProductNotFoundException;
import com.example.product.service.ProductService;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import java.math.BigDecimal;

public abstract class BaseProductContractTest {

    @BeforeEach
    void setup() {
        ProductService productService = Mockito.mock(ProductService.class);

        // Setup dữ liệu cho P-100
        Mockito.when(productService.getProductById("P-100"))
               .thenReturn(new ProductResponse("P-100", "Keyboard", new BigDecimal("120.50"), true));

        // Setup dữ liệu cho P-999999 (Not Found)
        Mockito.when(productService.getProductById("P-999999"))
               .thenThrow(new ProductNotFoundException("P-999999"));

        RestAssuredMockMvc.standaloneSetup(
            new ProductController(productService),
            new GlobalExceptionHandler()
        );
    }
}
```

### 3.3. Test tự động sinh ra bởi Plugin (`ContractVerifierTest.java`)

Khi chạy `mvn test` hoặc `mvn verify`, Spring Cloud Contract tự động sinh ra đoạn test sau tại `target/generated-test-sources/contracts/`:

```java
@Test
public void validate_should_return_product_when_id_exists() throws Exception {
    // given:
    MockMvcRequestSpecification request = given()
            .header("Accept", "application/json");

    // when:
    ResponseOptions response = given().spec(request)
            .get("/products/P-100");

    // then:
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.header("Content-Type")).matches("application/json.*");

    // and:
    DocumentContext parsedJson = JsonPath.parse(response.getBody().asString());
    assertThatJson(parsedJson).field("['id']").matches("^P-[0-9]{3,6}$");
    assertThatJson(parsedJson).field("['name']").matches("^.+$");
    assertThatJson(parsedJson).field("['price']").matches("^[0-9]+(\\.[0-9]{1,2})?$");
    assertThatJson(parsedJson).field("['available']").matches("^(true|false)$");
}
```

> **Điều gì xảy ra nếu Product Team đổi sang `{ "id": "P-100", "productName": "Keyboard", "unitPrice": 120.50 }`?**
> Ngay tại máy dev hoặc trong PR của Product Service, `validate_should_return_product_when_id_exists()` sẽ **FAIL** ngay lập tức ở 3 dòng assertion (`['name']`, `['price']`, và `['available']`). Không thể build ra `product-service-stubs.jar` và không thể merge PR!

---

## 4. Triển khai phía Consumer (Order Service)

Thay vì dùng `Mockito` hoặc tự viết chuỗi JSON giả trong `@MockBean` (nguyên nhân khiến Unit Test vẫn xanh dù Producer đã đổi API), **Order Service** sử dụng `StubRunner` nạp trực tiếp `product-service-stubs.jar` đã được verify từ Producer:

```java
package com.example.order.client;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureStubRunner(
    ids = "com.example:product-service:+:stubs:8081",
    stubsMode = StubRunnerProperties.StubsMode.REMOTE,
    repositoryRoot = "https://nexus.company.internal/repository/maven-releases/"
)
class ProductClientContractTest {

    @Autowired
    private ProductClient productClient; // Cấu hình trỏ tới http://localhost:8081

    @Test
    void shouldFetchProductDetailsFromVerifiedStub() {
        ProductDto product = productClient.getProductById("P-100");

        assertThat(product.id()).isEqualTo("P-100");
        assertThat(product.name()).isEqualTo("Keyboard");
        assertThat(product.price()).isEqualByComparingTo(new BigDecimal("120.50"));
        assertThat(product.available()).isTrue();
    }
}
```

