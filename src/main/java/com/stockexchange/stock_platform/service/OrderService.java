package com.stockexchange.stock_platform.service;

import com.stockexchange.stock_platform.dto.OrderDto;
import com.stockexchange.stock_platform.model.enums.OrderSide;
import com.stockexchange.stock_platform.model.enums.OrderStatus;
import com.stockexchange.stock_platform.model.enums.OrderType;

import java.math.BigDecimal;
import java.util.List;

public interface OrderService {
    OrderDto placeOrder(Long userId, String symbol, OrderType type, OrderSide side, BigDecimal quantity, BigDecimal price);
    OrderDto getOrder(Long orderId);
    List<OrderDto> getUserOrders(Long userId);
    List<OrderDto> getUserOrdersByStatus(Long userId, OrderStatus status);
    OrderDto cancelOrder(Long orderId);
    void processOrders();
}