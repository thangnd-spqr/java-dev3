package com.example.orderservice.adapter.in.web;

import com.example.orderservice.adapter.out.payment.PaymentMode;
import com.example.orderservice.adapter.out.payment.PaymentServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller để điều khiển mode giả lập của Payment Service trong runtime.
 *
 * Endpoints:
 *   GET  /api/simulator/payment-mode          → lấy mode hiện tại
 *   PUT  /api/simulator/payment-mode/{mode}   → đổi mode (SUCCESS | TIMEOUT | ALWAYS_FAIL)
 *
 * Demo flow:
 *   1. PUT /api/simulator/payment-mode/SUCCESS   → CB đóng lại (CLOSED)
 *   2. PUT /api/simulator/payment-mode/ALWAYS_FAIL → gọi liên tục để CB mở (OPEN)
 *   3. Sau 30s → CB tự chuyển HALF-OPEN
 *   4. PUT /api/simulator/payment-mode/SUCCESS   → CB đóng lại
 */
@Slf4j
@RestController
@RequestMapping("/api/simulator")
@RequiredArgsConstructor
public class PaymentSimulatorController {

    private final PaymentServiceClient paymentServiceClient;

    @GetMapping("/payment-mode")
    public ResponseEntity<Map<String, String>> getCurrentMode() {
        return ResponseEntity.ok(Map.of(
                "currentMode", paymentServiceClient.getMode().name(),
                "availableModes", "SUCCESS, TIMEOUT, ALWAYS_FAIL"
        ));
    }

    @PutMapping("/payment-mode/{mode}")
    public ResponseEntity<Map<String, String>> setMode(@PathVariable String mode) {
        try {
            PaymentMode paymentMode = PaymentMode.valueOf(mode.toUpperCase());
            paymentServiceClient.setMode(paymentMode);
            log.info("[SIMULATOR] Mode set to: {}", paymentMode);
            return ResponseEntity.ok(Map.of(
                    "message", "Payment mode changed to " + paymentMode,
                    "currentMode", paymentMode.name()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid mode: " + mode,
                    "availableModes", "SUCCESS, TIMEOUT, ALWAYS_FAIL"
            ));
        }
    }
}

