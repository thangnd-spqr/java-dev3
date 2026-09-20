package com.example.orderservice.application.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class OrderItemCommand {
    private String productId;
    private int quantity;
    private double price;
}
