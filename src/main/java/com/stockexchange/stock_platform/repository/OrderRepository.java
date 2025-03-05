package com.stockexchange.stock_platform.repository;

import com.stockexchange.stock_platform.model.entity.Order;
import com.stockexchange.stock_platform.model.entity.User;
import com.stockexchange.stock_platform.model.enums.OrderSide;
import com.stockexchange.stock_platform.model.enums.OrderStatus;
import com.stockexchange.stock_platform.model.enums.OrderType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // Find all orders for a specific user
    List<Order> findByUser(User user);

    // Find all orders for a specific user ID
    List<Order> findByUserId(Long userId);

    // Find orders by status (PENDING, EXECUTED, CANCELED, FAILED)
    List<Order> findByStatus(OrderStatus status);

    // Find orders by user and status
    List<Order> findByUserAndStatus(User user, OrderStatus status);

    // Find orders by user ID and status
    List<Order> findByUserIdAndStatus(Long userId, OrderStatus status);

    // Find orders by symbol and status
    List<Order> findBySymbolAndStatus(String symbol, OrderStatus status);

    // Find orders by user, symbol and status
    List<Order> findByUserAndSymbolAndStatus(User user, String symbol, OrderStatus status);

    // Find orders by side (BUY/SELL)
    List<Order> findBySide(OrderSide side);

    // Find orders by type (MARKET/LIMIT)
    List<Order> findByOrderType(OrderType orderType);

    // Find pending limit orders for a specific symbol
    List<Order> findBySymbolAndStatusAndOrderType(
            String symbol, OrderStatus status, OrderType orderType);
}