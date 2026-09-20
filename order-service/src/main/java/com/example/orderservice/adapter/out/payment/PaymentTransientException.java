package com.example.orderservice.adapter.out.payment;

/**
 * Exception dùng để đánh dấu lỗi tạm thời (transient) từ Payment Service.
 * Resilience4j sẽ retry khi gặp exception này.
 * Khác với lỗi vĩnh viễn (vd: sai thông tin thẻ) — không nên retry.
 */
public class PaymentTransientException extends RuntimeException {

    public PaymentTransientException(String message) {
        super(message);
    }

    public PaymentTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}

