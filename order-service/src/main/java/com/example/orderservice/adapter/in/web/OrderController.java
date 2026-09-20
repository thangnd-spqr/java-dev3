package com.example.orderservice.adapter.in.web;

import com.example.orderservice.adapter.in.web.dto.CreateOrderRequest;
import com.example.orderservice.application.dto.CreateOrderCommand;
import com.example.orderservice.application.dto.OrderItemCommand;
import com.example.orderservice.application.dto.OrderResponse;
import com.example.orderservice.application.port.in.CreateOrderUseCase;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final CreateOrderUseCase createOrderUseCase;

    public OrderController(CreateOrderUseCase createOrderUseCase) {
        this.createOrderUseCase = createOrderUseCase;
    }

    @PostMapping
    public OrderResponse createOrder(@RequestBody CreateOrderRequest request) {
        CreateOrderCommand command = new CreateOrderCommand(
            request.getOrderId(),
            request.getItems().stream()
                .map(item -> new OrderItemCommand(item.getProductId(), item.getQuantity(), item.getPrice()))
                .collect(Collectors.toList())
        );
        return createOrderUseCase.execute(command);
    }
}
