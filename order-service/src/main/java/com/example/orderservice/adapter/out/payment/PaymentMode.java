package com.example.orderservice.adapter.out.payment;

/**
 * Enum dùng để giả lập các tình huống của Payment Service:
 * - SUCCESS: Payment phản hồi thành công
 * - TIMEOUT: Payment bị timeout (giả lập bằng sleep)
 * - ALWAYS_FAIL: Payment trả lỗi liên tục → kích hoạt Circuit Breaker OPEN
 */
public enum PaymentMode {
    SUCCESS,
    TIMEOUT,
    ALWAYS_FAIL
}

