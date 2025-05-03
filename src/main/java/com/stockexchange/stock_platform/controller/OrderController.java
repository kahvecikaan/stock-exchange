package com.stockexchange.stock_platform.controller;

import com.stockexchange.stock_platform.dto.OrderDto;
import com.stockexchange.stock_platform.dto.PlaceOrderRequestDto;
import com.stockexchange.stock_platform.model.enums.OrderStatus;
import com.stockexchange.stock_platform.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderDto> placeOrder(@Valid @RequestBody PlaceOrderRequestDto orderRequest) {
        log.info("Placing {} for user ID: {}, symbol: {}, quantity: {}",
                orderRequest.getUserId(),
                orderRequest.getOrderType(),
                orderRequest.getSymbol(),
                orderRequest.getQuantity());

        OrderDto order = orderService.placeOrder(
                orderRequest.getUserId(),
                orderRequest.getSymbol(),
                orderRequest.getOrderType(),
                orderRequest.getSide(),
                orderRequest.getQuantity(),
                orderRequest.getPrice()
        );

        return ResponseEntity.ok(order);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDto> getOrder(@PathVariable Long orderId) {
        log.info("Fetching order for user ID: {}", orderId);
        OrderDto order = orderService.getOrder(orderId);

        return ResponseEntity.ok(order);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<OrderDto>> getUserOrders(@PathVariable Long userId) {
        log.info("Fetching all orders for user ID: {}", userId);
        List<OrderDto> orders = orderService.getUserOrders(userId);

        return ResponseEntity.ok(orders);
    }

    @GetMapping("/user/{userId}/status/{status}")
    public ResponseEntity<List<OrderDto>> getUserOrdersByStatus(
            @PathVariable Long userId,
            @PathVariable OrderStatus status) {
        log.info("Fetching {} orders for user ID: {}", status, userId);
        List<OrderDto> orders = orderService.getUserOrdersByStatus(userId, status);

        return ResponseEntity.ok(orders);
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderDto> cancelOrder(@PathVariable Long orderId) {
        log.info("Cancelling order with ID: {}", orderId);
        OrderDto order = orderService.cancelOrder(orderId);

        return ResponseEntity.ok(order);
    }

    @PostMapping("/process")
    public ResponseEntity<Void> processOrder() {
        log.info("Manually triggering order processing");
        orderService.processOrders();

        return ResponseEntity.ok().build();
    }
}
