package com.example.orderservice.application.service;

import com.example.orderservice.application.dto.CreateOrderCommand;
import com.example.orderservice.application.dto.OrderItemCommand;
import com.example.orderservice.application.dto.OrderResponse;
import com.example.orderservice.application.port.out.OrderRepository;
import com.example.orderservice.domain.exception.DomainException;
import com.example.orderservice.domain.valueobject.OrderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CreateOrderServiceTest {

    private OrderRepository orderRepository;
    private CreateOrderService createOrderService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        createOrderService = new CreateOrderService(orderRepository);
    }

    @Test
    void testCreateOrderSuccessfully() {
        CreateOrderCommand command = new CreateOrderCommand(
            "ORD-001",
            List.of(new OrderItemCommand("PROD-01", 2, 50.0))
        );

        when(orderRepository.findById(new OrderId("ORD-001"))).thenReturn(Optional.empty());

        OrderResponse response = createOrderService.execute(command);

        assertNotNull(response);
        assertEquals("ORD-001", response.getOrderId());
        assertEquals(100.0, response.getTotal());
        assertEquals("PENDING", response.getStatus());

        verify(orderRepository, times(1)).save(any());
    }

    @Test
    void testCreateOrderThrowsExceptionWhenOrderExists() {
        CreateOrderCommand command = new CreateOrderCommand(
            "ORD-001",
            List.of(new OrderItemCommand("PROD-01", 2, 50.0))
        );

        when(orderRepository.findById(new OrderId("ORD-001")))
            .thenReturn(Optional.of(Mockito.mock(com.example.orderservice.domain.entity.Order.class)));

        assertThrows(DomainException.class, () -> createOrderService.execute(command));
        verify(orderRepository, never()).save(any());
    }
    
    @Test
    void testCreateOrderWithoutItemsThrowsException() {
        CreateOrderCommand command = new CreateOrderCommand("ORD-002", List.of());
        when(orderRepository.findById(new OrderId("ORD-002"))).thenReturn(Optional.empty());

        assertThrows(DomainException.class, () -> createOrderService.execute(command));
    }
}\n