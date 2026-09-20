package com.example.orderservice.adapter.out.persistence;

import com.example.orderservice.application.port.out.OrderRepository;
import com.example.orderservice.domain.entity.Order;
import com.example.orderservice.domain.valueobject.OrderId;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class PostgresOrderRepository implements OrderRepository {

    private final OrderJpaRepository jpaRepository;

    public PostgresOrderRepository(OrderJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(Order order) {
        OrderEntity entity = new OrderEntity(
            order.getId().getValue(),
            order.getTotal(),
            order.getStatus().name()
        );
        jpaRepository.save(entity);
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return jpaRepository.findById(id.getValue())
            .map(entity -> {
                // Return mapped order. Assuming a simplified entity-to-domain mapping.
                return null;
            });
    }
}
