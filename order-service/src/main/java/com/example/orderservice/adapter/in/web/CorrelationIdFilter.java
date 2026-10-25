package com.example.orderservice.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Đảm bảo mọi HTTP request đi qua order-service đều có traceId và correlationId
 * trong MDC (phục vụ Structured Logging) và trả về trong Response Header để đối soát sự cố.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String W3C_TRACEPARENT_HEADER = "traceparent";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = Optional.ofNullable(request.getHeader(CORRELATION_ID_HEADER))
                .filter(id -> !id.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());

        String traceId = extractOrGenerateTraceId(request, correlationId);

        MDC.put("correlationId", correlationId);
        MDC.put("traceId", traceId);

        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
            MDC.remove("traceId");
            MDC.remove("orderId");
        }
    }

    private String extractOrGenerateTraceId(HttpServletRequest request, String fallbackId) {
        String traceparent = request.getHeader(W3C_TRACEPARENT_HEADER);
        if (traceparent != null && traceparent.split("-").length >= 2) {
            return traceparent.split("-")[1];
        }
        String xTraceId = request.getHeader(TRACE_ID_HEADER);
        if (xTraceId != null && !xTraceId.isBlank()) {
            return xTraceId;
        }
        return fallbackId.replace("-", "");
    }
}

