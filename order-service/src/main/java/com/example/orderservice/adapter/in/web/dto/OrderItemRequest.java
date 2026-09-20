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
