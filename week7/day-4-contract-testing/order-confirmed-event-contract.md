# Messaging Contract: Order Service → Notification Service (`OrderConfirmed` Event)

## 1. Bối cảnh nghiệp vụ & Tại sao cần Messaging Contract?

Trong kiến trúc Event-Driven Microservices:
- Sau khi khách hàng thanh toán và đặt hàng thành công, **Order Service (Producer)** phát (publish) một sự kiện `OrderConfirmed` lên Message Broker (Kafka topic hoặc RabbitMQ exchange `order.events`).
- **Notification Service (Consumer)** lắng nghe (subscribe) trên `order.events` để gửi email/SMS xác nhận đơn hàng cho khách.

### Rủi ro khi không có Messaging Contract
Không giống HTTP (nơi lỗi 400/500 trả về ngay cho caller), giao tiếp bất đồng bộ qua Message Broker **ẩn giấu lỗi cực kỳ nguy hiểm**:
- Nếu **Order Service** đổi tên trường `customerEmail` thành `emailAddress` hoặc đổi định dạng `totalAmount`:
  - Order Service publish message lên Kafka vẫn thành công 100% (Broker không kiểm tra schema nội dung nếu chỉ gửi JSON thô).
  - Notification Service đọc message bị `DeserializationException` hoặc nhận `customerEmail = null`, đẩy message vào Dead Letter Queue (DLQ) âm thầm mà không ai biết cho đến khi khách hàng khiếu nại không nhận được email xác nhận.

---

## 2. Đặc tả Message Contract (`OrderConfirmed`)

### 2.1. Cấu trúc Payload chuẩn thỏa thuận giữa 2 team

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId": "ORD-2026-0001",
  "customerId": "CUST-889",
  "customerEmail": "customer@example.com",
  "items": [
    {
      "productId": "P-100",
      "productName": "Keyboard",
      "quantity": 2,
      "unitPrice": 120.50
    }
  ],
  "totalAmount": 241.00,
  "currency": "USD",
  "confirmedAt": "2026-10-25T07:30:00Z"
}
```

---

### 2.2. Định dạng YAML DSL (`src/test/resources/contracts/notification-consumer/shouldPublishOrderConfirmedEvent.yml`)

```yaml
description: |
  Order Service phát sự kiện OrderConfirmed lên topic 'order.events' sau khi đơn hàng được xác nhận.
  Notification Service sử dụng contract này để gửi email xác nhận đơn hàng.
name: should_publish_order_confirmed_event
label: trigger_order_confirmed_event

input:
  # Hàm trong Base Test Class của Producer sẽ được gọi để kích hoạt việc bắn message
  triggeredBy: emitOrderConfirmedEvent()

outputMessage:
  # Tên destination (Kafka topic hoặc RabbitMQ exchange)
  sentTo: order.events
  headers:
    contentType: application/json
    eventType: OrderConfirmed
    kafka_messageKey: "ORD-2026-0001"
  body:
    eventId: "550e8400-e29b-41d4-a716-446655440000"
    orderId: "ORD-2026-0001"
    customerId: "CUST-889"
    customerEmail: "customer@example.com"
    items:
      - productId: "P-100"
        productName: "Keyboard"
        quantity: 2
        unitPrice: 120.50
    totalAmount: 241.00
    currency: "USD"
    confirmedAt: "2026-10-25T07:30:00Z"
  matchers:
    headers:
      - key: contentType
        regex: "application/json.*"
      - key: eventType
        regex: "^OrderConfirmed$"
    body:
      - path: $.eventId
        type: by_regex
        value: "^[0-9a-fA-F-]{36}$"
      - path: $.orderId
        type: by_regex
        value: "^ORD-[0-9]{4}-[0-9]{4,8}$"
      - path: $.customerId
        type: by_regex
        value: "^CUST-[0-9]+$"
      - path: $.customerEmail
        type: by_regex
        value: "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$"
      - path: $.items[0].productId
        type: by_regex
        value: "^P-[0-9]{3,6}$"
      - path: $.items[0].quantity
        type: by_regex
        value: "^[1-9][0-9]*$"
      - path: $.totalAmount
        type: by_regex
        value: "^[0-9]+(\\.[0-9]{1,2})?$"
      - path: $.currency
        type: by_regex
        value: "^[A-Z]{3}$"
      - path: $.confirmedAt
        type: by_regex
        value: "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$"
```

---

### 2.3. Định dạng Groovy DSL (`src/test/resources/contracts/notification-consumer/shouldPublishOrderConfirmedEvent.groovy`)

```groovy
package contracts.notification_consumer

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description("Order Service phát sự kiện OrderConfirmed lên topic order.events")
    name("should_publish_order_confirmed_event")
    label("trigger_order_confirmed_event")

    input {
        triggeredBy("emitOrderConfirmedEvent()")
    }

    outputMessage {
        sentTo("order.events")
        headers {
            messagingContentType(applicationJson())
            header("eventType", "OrderConfirmed")
            header("kafka_messageKey", value(consumer("ORD-2026-0001"), producer(regex('^ORD-[0-9]{4}-[0-9]{4,8}$'))))
        }
        body([
            eventId      : value(consumer("550e8400-e29b-41d4-a716-446655440000"), producer(anyUuid())),
            orderId      : value(consumer("ORD-2026-0001"), producer(regex('^ORD-[0-9]{4}-[0-9]{4,8}$'))),
            customerId   : value(consumer("CUST-889"), producer(regex('^CUST-[0-9]+$'))),
            customerEmail: value(consumer("customer@example.com"), producer(anyEmail())),
            items        : [[
                productId  : value(consumer("P-100"), producer(regex('^P-[0-9]{3,6}$'))),
                productName: value(consumer("Keyboard"), producer(anyNonBlankString())),
                quantity   : value(consumer(2), producer(anyPositiveInt())),
                unitPrice  : value(consumer(120.50), producer(anyDouble()))
            ]],
            totalAmount  : value(consumer(241.00), producer(anyDouble())),
            currency     : value(consumer("USD"), producer(regex('^[A-Z]{3}$'))),
            confirmedAt  : value(consumer("2026-10-25T07:30:00Z"), producer(anyIso8601WithOffset()))
        ])
    }
}
```

---

## 3. Triển khai Provider Verification trên Order Service (Producer)

Ở phía **Order Service**, khi chạy `mvn verify`, plugin sẽ gọi hàm `emitOrderConfirmedEvent()` trong Base Test Class và bắt gói tin gửi ra từ Spring Cloud Stream (`TestBinder`) để đối chiếu với `outputMessage`.

```java
package com.example.order.contract;

import com.example.order.domain.Order;
import com.example.order.domain.OrderItem;
import com.example.order.messaging.OrderEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.verifier.messaging.boot.AutoConfigureMessageVerifier;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureMessageVerifier
@Import(TestChannelBinderConfiguration.class)
public abstract class BaseOrderMessagingContractTest {

    @Autowired
    private OrderEventPublisher orderEventPublisher;

    /**
     * Hàm được chỉ định trong 'input.triggeredBy' của file Contract
     */
    public void emitOrderConfirmedEvent() {
        Order order = new Order(
            "ORD-2026-0001",
            "CUST-889",
            "customer@example.com",
            List.of(new OrderItem("P-100", "Keyboard", 2, new BigDecimal("120.50"))),
            new BigDecimal("241.00"),
            "USD",
            Instant.parse("2026-10-25T07:30:00Z")
        );

        orderEventPublisher.publishOrderConfirmed(order);
    }
}
```

---

## 4. Triển khai Consumer Test trên Notification Service (Consumer)

Ở phía **Notification Service**, chúng ta dùng `StubTrigger` để giả lập việc Order Service vừa bắn sự kiện `trigger_order_confirmed_event` (label đã khai báo trong Contract) vào channel lắng nghe của Notification Service:

```java
package com.example.notification.listener;

import com.example.notification.service.EmailSenderService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.contract.stubrunner.StubTrigger;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureStubRunner(
    ids = "com.example:order-service:+:stubs",
    stubsMode = StubRunnerProperties.StubsMode.REMOTE,
    repositoryRoot = "https://nexus.company.internal/repository/maven-releases/"
)
@Import(TestChannelBinderConfiguration.class)
class OrderConfirmedEventListenerContractTest {

    @Autowired
    private StubTrigger stubTrigger;

    @MockBean
    private EmailSenderService emailSenderService;

    @Test
    void shouldConsumeOrderConfirmedEventAndSendEmail() {
        // When: Kích hoạt message từ Stub của Order Service theo đúng label trong Contract
        stubTrigger.trigger("trigger_order_confirmed_event");

        // Then: Kiểm chứng Notification Service deserialize đúng và gọi gửi email
        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        verify(emailSenderService).sendEmail(
            eq("customer@example.com"),
            subjectCaptor.capture(),
            bodyCaptor.capture()
        );

        assertThat(subjectCaptor.getValue()).contains("ORD-2026-0001");
        assertThat(bodyCaptor.getValue()).contains("Keyboard", "241.00");
    }
}
```

