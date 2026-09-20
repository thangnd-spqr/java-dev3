package com.example.orderservice.application.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * DTO kết quả từ Payment Service.
 * success=false nghĩa là dùng fallback (CB đang OPEN hoặc lỗi).
 */
@Getter
@AllArgsConstructor
public class PaymentResponse {

    private final String paymentId;
    private final boolean success;
    private final String message;

    public static PaymentResponse fallback(String reason) {
        return new PaymentResponse("FALLBACK-" + System.currentTimeMillis(), false, reason);
    }
}

