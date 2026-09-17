package com.example.orderservice.application.port.in;

import com.example.orderservice.application.dto.CreateOrderCommand;
import com.example.orderservice.application.dto.OrderResponse;

public interface CreateOrderUseCase {
    OrderResponse execute(CreateOrderCommand command);
}\n