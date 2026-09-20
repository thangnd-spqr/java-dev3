package com.example.orderservice.application.port.out;

import com.example.orderservice.application.dto.PaymentResponse;

/**
 * Output port cho Payment Service.
 * Tuân theo Clean Architecture: Application layer không biết implementation chi tiết.
 */
public interface PaymentServicePort {

    /**
     * Thực hiện thanh toán cho đơn hàng.
     *
     * @param orderId  ID của đơn hàng
     * @param amount   Số tiền cần thanh toán
     * @return PaymentResponse — thành công hoặc fallback nếu CB đang OPEN
     */
    PaymentResponse charge(String orderId, double amount);
}

