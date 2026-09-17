package com.example.orderservice.application.service;

import com.example.orderservice.application.dto.CreateOrderCommand;
import com.example.orderservice.application.dto.OrderResponse;
import com.example.orderservice.application.port.in.CreateOrderUseCase;
import com.example.orderservice.application.port.out.OrderRepository;
import com.example.orderservice.domain.entity.Order;
import com.example.orderservice.domain.entity.OrderItem;
import com.example.orderservice.domain.exception.DomainException;
import com.example.orderservice.domain.valueobject.OrderId;
import com.example.orderservice.domain.valueobject.ProductId;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class CreateOrderService implements CreateOrderUseCase {

    private final OrderRepository orderRepository;

    public CreateOrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public OrderResponse execute(CreateOrderCommand command) {
        if (orderRepository.findById(new OrderId(command.getOrderId())).isPresent()) {
            throw new DomainException("Order with ID " + command.getOrderId() + " already exists");
        }

        Order order = new Order(
            new OrderId(command.getOrderId()),
            command.getItems().stream()
                .map(item -> new OrderItem(
                    new ProductId(item.getProductId()),
                    item.getQuantity(),
                    item.getPrice()
                )).collect(Collectors.toList())
        );

        orderRepository.save(order);

        return new OrderResponse(
            order.getId().getValue(),
            order.getTotal(),
            order.getStatus().name()
        );
    }
}\n