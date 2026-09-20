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
