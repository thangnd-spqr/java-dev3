package com.example.orderservice.domain.entity;

import com.example.orderservice.domain.valueobject.ProductId;
import lombok.Getter;

@Getter
public class OrderItem {
    private final ProductId productId;
    private final int quantity;
    private final double price;

    public OrderItem(ProductId productId, int quantity, double price) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be greater than 0");
        if (price < 0) throw new IllegalArgumentException("Price cannot be negative");
        this.productId = productId;
        this.quantity = quantity;
        this.price = price;
    }
    
    public double getSubtotal() {
        return price * quantity;
    }
}\n