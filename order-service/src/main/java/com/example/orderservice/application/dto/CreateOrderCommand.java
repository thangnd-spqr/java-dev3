package com.example.orderservice.application.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

@Getter
@AllArgsConstructor
public class CreateOrderCommand {
    private String orderId;
    private List<OrderItemCommand> items;
}
