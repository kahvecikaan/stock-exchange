package com.stockexchange.stock_platform.pattern.factory;

import com.stockexchange.stock_platform.model.enums.OrderSide;
import com.stockexchange.stock_platform.service.UserService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class LimitOrderRequestFactory implements OrderRequestFactory {

    private final UserService userService;

    public LimitOrderRequestFactory(UserService userService) {
        this.userService = userService;
    }

    @Override
    public OrderRequest createOrderRequest(Long userId, String symbol, OrderSide side,
                                           BigDecimal quantity, BigDecimal price) {
        if (price == null) {
            throw new IllegalArgumentException("Limit orders require a price");
        }
        return new LimitOrderRequest(userId, symbol, side, quantity, price, userService);
    }
}