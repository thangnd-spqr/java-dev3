package com.example.orderservice.adapter.in.web.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class CreateOrderRequest {
    private String orderId;
    private List<OrderItemRequest> items;
}
