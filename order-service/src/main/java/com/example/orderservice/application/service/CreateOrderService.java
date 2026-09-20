package com.example.orderservice.application.service;

import com.example.orderservice.application.dto.CreateOrderCommand;
import com.example.orderservice.application.dto.OrderResponse;
import com.example.orderservice.application.dto.PaymentResponse;
import com.example.orderservice.application.port.in.CreateOrderUseCase;
import com.example.orderservice.application.port.out.OrderRepository;
import com.example.orderservice.application.port.out.PaymentServicePort;
import com.example.orderservice.domain.entity.Order;
import com.example.orderservice.domain.entity.OrderItem;
import com.example.orderservice.domain.exception.DomainException;
import com.example.orderservice.domain.valueobject.OrderId;
import com.example.orderservice.domain.valueobject.ProductId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Slf4j
@Service
public class CreateOrderService implements CreateOrderUseCase {

    private final OrderRepository orderRepository;
    private final PaymentServicePort paymentServicePort;

    public CreateOrderService(OrderRepository orderRepository,
                               PaymentServicePort paymentServicePort) {
        this.orderRepository = orderRepository;
        this.paymentServicePort = paymentServicePort;
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

        // ============================================================
        // Gọi Payment Service qua Circuit Breaker + Retry
        // - Nếu payment thành công: lưu đơn hàng bình thường
        // - Nếu CB đang OPEN (fallback): lưu đơn hàng với trạng thái PENDING_PAYMENT
        // ============================================================
        PaymentResponse payment = paymentServicePort.charge(
                command.getOrderId(),
                order.getTotal()
        );

        if (!payment.isSuccess()) {
            // Fallback behavior: Lưu order với payment pending
            // (Circuit Breaker đang OPEN, payment service không khả dụng)
            log.warn("[ORDER] Payment fallback triggered for orderId={}. Message: {}",
                    command.getOrderId(), payment.getMessage());
        } else {
            log.info("[ORDER] Payment success for orderId={}, paymentId={}",
                    command.getOrderId(), payment.getPaymentId());
        }

        orderRepository.save(order);

        return new OrderResponse(
            order.getId().getValue(),
            order.getTotal(),
            order.getStatus().name()
        );
    }
}