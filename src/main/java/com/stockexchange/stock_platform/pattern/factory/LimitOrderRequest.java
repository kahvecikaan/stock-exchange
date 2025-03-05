package com.stockexchange.stock_platform.pattern.factory;

import com.stockexchange.stock_platform.model.entity.User;
import com.stockexchange.stock_platform.model.enums.OrderSide;
import com.stockexchange.stock_platform.model.enums.OrderType;
import com.stockexchange.stock_platform.service.UserService;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class LimitOrderRequest implements OrderRequest {
    private final Long userId;
    private final String symbol;
    private final OrderSide side;
    private final BigDecimal quantity;
    private final BigDecimal price;

    private final UserService userService;

    public LimitOrderRequest(Long userId, String symbol, OrderSide side,
                             BigDecimal quantity, BigDecimal price,
                             UserService userService) {
        this.userId = userId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.userService = userService;
    }

    @Override
    public OrderType getOrderType() {
        return OrderType.LIMIT;
    }

    @Override
    public boolean validate() {
        User user = userService.getUserById(userId);

        if (side == OrderSide.BUY) {
            // Check if user has enough cash for the purchase
            BigDecimal orderCost = price.multiply(quantity);
            return user.getCashBalance().compareTo(orderCost) >= 0;
        } else if (side == OrderSide.SELL) {
            // Check if user has enough shares to sell
            return userService.hasEnoughShares(userId, symbol, quantity);
        }

        return false;
    }
}