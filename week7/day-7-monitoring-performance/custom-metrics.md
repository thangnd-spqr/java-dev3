# Custom Metrics — Order Service

## 1. Chọn đúng loại meter

| Cần đo | Meter | Metric name (Micrometer → Prometheus) |
|---|---|---|
| Số checkout thành công | **Counter** | `checkout.success` → `checkout_success_total` |
| Số checkout thất bại theo lý do | **Counter** + tag `reason` | `checkout.failed` → `checkout_failed_total{reason}` |
| Payment trả về trạng thái không rõ (timeout sau khi gateway đã nhận) | **Counter** | `payment.status.unknown` → `payment_status_unknown_total` |
| Số order kẹt ở `PAYMENT_PROCESSING` > 5 phút | **Gauge** | `order.stuck.processing` → `order_stuck_processing` |
| Thời gian checkout end-to-end | **Timer** (histogram + SLO buckets) | `checkout.duration` → `checkout_duration_seconds_*` |
| Giá trị đơn hàng (revenue, phân phối) | **DistributionSummary** | `checkout.amount` → `checkout_amount_*` |
| Outbox backlog | **Gauge** | `outbox.pending.events`, `outbox.oldest.pending.age.seconds` |
| Job reconciliation chạy lâu | **LongTaskTimer** | `reconciliation.job` → `reconciliation_job_seconds_active_count` |

Quy tắc:

- **Counter**: chỉ tăng; xem bằng `rate()` / `increase()`.
- **Gauge**: giá trị hiện tại (tăng/giảm), Micrometer giữ **weak reference** tới object → phải giữ reference mạnh (field trong bean).
- **Timer**: đo cả count + tổng thời gian; bật `publishPercentileHistogram` để tính p95/p99 **aggregate được qua nhiều pod** (client-side percentile không cộng được giữa các pod).
- Tag chỉ dùng giá trị **hữu hạn, ít** (enum). Không dùng `orderId`, `userId`, `email`, `productId`, `traceId`, message exception.

## 2. Common tags

Cấu hình trong [application-observability.yml](application-observability.yml):

```yaml
management:
  metrics:
    tags:
      application: ${spring.application.name}
      environment: ${APP_ENV:local}
      version: ${APP_VERSION:unknown}
```

Kết quả: `checkout_success_total{application="order-service",environment="prod",version="1.4.2"} 1532`.

## 3. Implementation

### `CheckoutMetrics.java`

```java
package com.example.orderservice.adapter.out.metrics;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

@Component
public class CheckoutMetrics {

    /** Tập giá trị hữu hạn cho tag "reason" — tránh high cardinality. */
    public enum FailureReason {
        INVENTORY_UNAVAILABLE, OUT_OF_STOCK, PAYMENT_DECLINED,
        PAYMENT_TIMEOUT, VALIDATION, INTERNAL
    }

    private final Counter checkoutSuccess;
    private final Map<FailureReason, Counter> checkoutFailed = new EnumMap<>(FailureReason.class);
    private final Counter paymentStatusUnknown;
    private final Timer checkoutDuration;
    private final DistributionSummary checkoutAmount;

    public CheckoutMetrics(MeterRegistry registry) {
        this.checkoutSuccess = Counter.builder("checkout.success")
                .description("Number of successful checkouts")
                .register(registry);

        for (FailureReason reason : FailureReason.values()) {
            checkoutFailed.put(reason, Counter.builder("checkout.failed")
                    .description("Number of failed checkouts")
                    .tag("reason", reason.name())
                    .register(registry));
        }

        this.paymentStatusUnknown = Counter.builder("payment.status.unknown")
                .description("Payment call timed out / ambiguous result after request was accepted")
                .register(registry);

        this.checkoutDuration = Timer.builder("checkout.duration")
                .description("End-to-end checkout duration")
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(300), Duration.ofMillis(800), Duration.ofSeconds(2))
                .minimumExpectedValue(Duration.ofMillis(10))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(registry);

        this.checkoutAmount = DistributionSummary.builder("checkout.amount")
                .description("Order amount of successful checkouts")
                .baseUnit("vnd")
                .register(registry);
    }

    public void success(BigDecimal amount) {
        checkoutSuccess.increment();
        checkoutAmount.record(amount.doubleValue());
    }

    public void failed(FailureReason reason) {
        checkoutFailed.get(reason).increment();
    }

    public void paymentStatusUnknown() {
        paymentStatusUnknown.increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }

    public void stopTimer(Timer.Sample sample) {
        sample.stop(checkoutDuration);
    }
}
```

### `OrderStuckMetrics.java` (Gauge)

Gauge không nên chạy query DB **mỗi lần Prometheus scrape** (nhiều instance × scrape mỗi 15s, và scrape bị chậm khi DB chậm). Dùng job định kỳ cập nhật một `AtomicLong`:

```java
package com.example.orderservice.adapter.out.metrics;

import com.example.orderservice.adapter.out.persistence.OrderJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OrderStuckMetrics {

    private final OrderJpaRepository orderRepository;
    private final AtomicLong stuckProcessing = new AtomicLong();      // strong reference
    private final AtomicLong paymentSuccessOrderPending = new AtomicLong();

    public OrderStuckMetrics(OrderJpaRepository orderRepository, MeterRegistry registry) {
        this.orderRepository = orderRepository;
        Gauge.builder("order.stuck.processing", stuckProcessing, AtomicLong::get)
                .description("Orders in PAYMENT_PROCESSING for more than 5 minutes")
                .register(registry);
        Gauge.builder("payment.success.order.pending", paymentSuccessOrderPending, AtomicLong::get)
                .description("Payment captured but order not CONFIRMED for more than 5 minutes")
                .register(registry);
    }

    // Chỉ cần 1 instance chạy là đủ (dùng ShedLock) — dashboard dùng max() để không cộng trùng.
    @Scheduled(fixedDelayString = "${metrics.stuck-order.refresh:30s}")
    public void refresh() {
        Instant threshold = Instant.now().minus(5, ChronoUnit.MINUTES);
        stuckProcessing.set(orderRepository.countByStatusAndUpdatedAtBefore("PAYMENT_PROCESSING", threshold));
        paymentSuccessOrderPending.set(orderRepository.countPaymentCapturedButNotConfirmed(threshold));
    }
}
```

> Query cần index `(status, updated_at)`. Gauge chạy trên mọi pod sẽ báo cùng một giá trị → dashboard/alert dùng `max()` thay vì `sum()`.

### Sử dụng trong `CreateOrderService`

```java
public OrderResponse createOrder(CreateOrderCommand command) {
    Timer.Sample sample = checkoutMetrics.startTimer();
    try {
        reserveInventory(command);              // span inventory.reserve
        PaymentResponse payment = charge(command); // span payment.charge
        Order order = saveOrderAndOutbox(command, payment); // 1 transaction, KHÔNG gọi HTTP bên trong
        checkoutMetrics.success(order.getTotalAmount());
        log.info("event=ORDER_CREATED orderId={} amount={}", order.getId(), order.getTotalAmount());
        return OrderResponse.from(order);
    } catch (InventoryUnavailableException | CallNotPermittedException e) {
        checkoutMetrics.failed(FailureReason.INVENTORY_UNAVAILABLE);
        throw e;
    } catch (OutOfStockException e) {
        checkoutMetrics.failed(FailureReason.OUT_OF_STOCK);
        throw e;
    } catch (PaymentDeclinedException e) {
        checkoutMetrics.failed(FailureReason.PAYMENT_DECLINED);
        throw e;
    } catch (PaymentTimeoutException e) {
        // Payment có thể đã charge → KHÔNG báo fail cứng cho user, chuyển PAYMENT_PROCESSING + reconciliation
        checkoutMetrics.paymentStatusUnknown();
        checkoutMetrics.failed(FailureReason.PAYMENT_TIMEOUT);
        log.error("event=PAYMENT_STATUS_UNKNOWN orderId={}", command.orderId(), e);
        throw e;
    } catch (RuntimeException e) {
        checkoutMetrics.failed(FailureReason.INTERNAL);
        throw e;
    } finally {
        checkoutMetrics.stopTimer(sample);
    }
}
```

Cách khác (gọn hơn): dùng **Observation API** để vừa có metric vừa có span từ một chỗ:

```java
return Observation.createNotStarted("checkout", observationRegistry)
        .lowCardinalityKeyValue("channel", "web")       // → metric tag + span tag
        .highCardinalityKeyValue("order.id", orderId)  // → CHỈ span attribute, không thành metric tag
        .observe(() -> doCheckout(command));
```

## 4. Kiểm tra

```powershell
curl http://localhost:8080/actuator/metrics/checkout.success
curl "http://localhost:8080/actuator/metrics/checkout.failed?tag=reason:INVENTORY_UNAVAILABLE"
curl http://localhost:8080/actuator/prometheus | Select-String "checkout_|payment_status_unknown|order_stuck"
```

Output mẫu:

```text
# HELP checkout_success_total Number of successful checkouts
# TYPE checkout_success_total counter
checkout_success_total{application="order-service",environment="local",version="1.0.0"} 42.0
checkout_failed_total{application="order-service",environment="local",reason="INVENTORY_UNAVAILABLE",version="1.0.0"} 3.0
payment_status_unknown_total{application="order-service",environment="local",version="1.0.0"} 1.0
order_stuck_processing{application="order-service",environment="local",version="1.0.0"} 0.0
checkout_duration_seconds_bucket{application="order-service",environment="local",version="1.0.0",le="0.8"} 40.0
```

## 5. Checklist cardinality

| Tag | Số giá trị | OK? |
|---|---|---|
| `application`, `environment`, `version` | vài giá trị | ✅ |
| `reason` (enum) | 6 | ✅ |
| `uri` (template `/orders/{id}`) | ~10 | ✅ — **không** dùng URI thật `/orders/ORD-123` |
| `status`, `method`, `outcome` | ~10 | ✅ |
| `orderId`, `userId`, `email`, `traceId` | không giới hạn | ❌ → log/trace |
| `exception.message` | không giới hạn | ❌ → dùng `exception` class name (Spring mặc định) |

