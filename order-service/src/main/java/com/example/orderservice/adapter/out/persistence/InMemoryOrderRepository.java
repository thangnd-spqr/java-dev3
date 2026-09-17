package com.example.orderservice.adapter.out.persistence;

import com.example.orderservice.application.port.out.OrderRepository;
import com.example.orderservice.domain.entity.Order;
import com.example.orderservice.domain.valueobject.OrderId;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Primary
public class InMemoryOrderRepository implements OrderRepository {
    
    private final Map<String, Order> store = new ConcurrentHashMap<>();

    @Override
    public void save(Order order) {
        store.put(order.getId().getValue(), order);
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return Optional.ofNullable(store.get(id.getValue()));
    }
}\n