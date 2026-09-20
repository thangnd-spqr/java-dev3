package com.example.orderservice.domain.valueobject;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
public class OrderId {
    private final String value;
    public OrderId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("OrderId cannot be empty");
        }
        this.value = value;
    }
}
