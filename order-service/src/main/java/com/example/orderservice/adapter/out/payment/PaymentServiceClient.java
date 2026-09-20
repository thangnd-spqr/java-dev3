package com.example.orderservice.adapter.out.payment;

import com.example.orderservice.application.dto.PaymentResponse;
import com.example.orderservice.application.port.out.PaymentServicePort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapter giả lập Payment Service với đầy đủ các tình huống:
 *
 * 1. SUCCESS      — Payment phản hồi thành công
 * 2. TIMEOUT      — Payment bị timeout → kích hoạt Retry
 * 3. ALWAYS_FAIL  — Payment trả lỗi liên tục → kích hoạt Circuit Breaker OPEN
 *
 * Dùng Resilience4j:
 *  - @Retry(name = "paymentService")          → Thử lại tối đa 3 lần khi gặp lỗi transient
 *  - @CircuitBreaker(name = "paymentService") → Mở circuit khi failure rate >= 50%
 *                                               Fallback: chargeWithFallback()
 *
 * Thứ tự áp dụng: Retry bao ngoài → CircuitBreaker bên trong
 * (Retry sẽ thử lại nếu CB chưa mở; khi CB OPEN thì Retry cũng dừng)
 */
@Slf4j
@Component
public class PaymentServiceClient implements PaymentServicePort {

    // ============================================================
    // Simulator Mode — có thể thay đổi runtime qua API
    // ============================================================
    private final AtomicReference<PaymentMode> currentMode =
            new AtomicReference<>(PaymentMode.SUCCESS);

    public void setMode(PaymentMode mode) {
        log.info("[SIMULATOR] Payment mode changed: {} → {}", currentMode.get(), mode);
        currentMode.set(mode);
    }

    public PaymentMode getMode() {
        return currentMode.get();
    }

    // ============================================================
    // Main method — được bọc bởi @Retry rồi @CircuitBreaker
    // ============================================================

    /**
     * @Retry   → nếu ném PaymentTransientException hoặc TimeoutException, sẽ retry tối đa 3 lần
     * @CircuitBreaker → nếu có quá nhiều lỗi, mở CB và gọi chargeWithFallback()
     *
     * Chú ý: @Retry annotation phải đặt TRƯỚC @CircuitBreaker để Retry bao ngoài CB.
     */
    @Override
    @Retry(name = "paymentService")
    @CircuitBreaker(name = "paymentService", fallbackMethod = "chargeWithFallback")
    public PaymentResponse charge(String orderId, double amount) {
        PaymentMode mode = currentMode.get();
        log.info("[PAYMENT] Calling payment service — orderId={}, amount={}, mode={}", orderId, amount, mode);

        return switch (mode) {
            case SUCCESS -> handleSuccess(orderId, amount);
            case TIMEOUT -> handleTimeout(orderId);
            case ALWAYS_FAIL -> handleAlwaysFail(orderId);
        };
    }

    // ============================================================
    // Fallback — được gọi khi Circuit Breaker OPEN
    // ============================================================

    /**
     * Fallback behavior khi Circuit Breaker đang OPEN.
     * Trả về response có ý nghĩa: order được ghi nhận nhưng payment pending.
     *
     * Signature phải khớp với method chính + thêm Throwable parameter cuối.
     */
    @SuppressWarnings("unused")
    public PaymentResponse chargeWithFallback(String orderId, double amount, Throwable ex) {
        log.warn("[CIRCUIT BREAKER] OPEN — Payment service unavailable. orderId={}, reason={}",
                orderId, ex.getMessage());

        // Fallback behavior: ghi nhận đơn hàng, payment sẽ xử lý sau (pending)
        return PaymentResponse.fallback(
                "Payment service tạm thời không khả dụng. " +
                "Đơn hàng [" + orderId + "] đã được ghi nhận, " +
                "thanh toán sẽ được xử lý khi service phục hồi."
        );
    }

    // ============================================================
    // Simulator handlers
    // ============================================================

    /**
     * Tình huống 1: Payment service phản hồi thành công
     */
    private PaymentResponse handleSuccess(String orderId, double amount) {
        log.info("[PAYMENT] ✅ Success — orderId={}, amount={}", orderId, amount);
        return new PaymentResponse(
                "PAY-" + orderId + "-" + System.currentTimeMillis(),
                true,
                "Payment successful"
        );
    }

    /**
     * Tình huống 2: Payment service bị timeout.
     * Ném PaymentTransientException → Retry sẽ thử lại.
     * Nếu vẫn fail sau 3 lần → CB ghi nhận failure.
     */
    private PaymentResponse handleTimeout(String orderId) {
        log.warn("[PAYMENT] ⏱ Simulating timeout — orderId={}", orderId);
        try {
            // Giả lập network delay 5 giây
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Ném exception để Retry/CB xử lý
        throw new PaymentTransientException(
                "Payment service timeout after 5000ms — orderId=" + orderId
        );
    }

    /**
     * Tình huống 3: Payment service trả lỗi liên tục.
     * Ném PaymentTransientException → CB đếm failure.
     * Sau minimum-number-of-calls (5 calls) và failure-rate >= 50% → CB mở (OPEN).
     */
    private PaymentResponse handleAlwaysFail(String orderId) {
        log.error("[PAYMENT] ❌ Simulating persistent failure — orderId={}", orderId);
        throw new PaymentTransientException(
                "Payment service unavailable — orderId=" + orderId
        );
    }
}

