package com.example.orderservice.application.port.out;

import com.example.orderservice.domain.entity.Order;
import com.example.orderservice.domain.valueobject.OrderId;
import java.util.Optional;

public interface OrderRepository {
    void save(Order order);
    Optional<Order> findById(OrderId id);
}\n