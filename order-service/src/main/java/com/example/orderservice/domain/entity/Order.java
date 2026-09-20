package com.example.orderservice.domain.entity;

import com.example.orderservice.domain.exception.DomainException;
import com.example.orderservice.domain.valueobject.OrderId;
import com.example.orderservice.domain.valueobject.OrderStatus;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class Order {
    private final OrderId id;
    private final List<OrderItem> items;
    private OrderStatus status;

    public Order(OrderId id, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new DomainException("Order must have at least one item");
        }
        this.id = id;
        this.items = new ArrayList<>(items);
        this.status = OrderStatus.PENDING;
    }

    public double getTotal() {
        return items.stream()
            .mapToDouble(OrderItem::getSubtotal)
            .sum();
    }

    public void confirm() {
        if (this.status != OrderStatus.PENDING) {
            throw new DomainException("Can only confirm PENDING orders");
        }
        this.status = OrderStatus.CONFIRMED;
    }
}
