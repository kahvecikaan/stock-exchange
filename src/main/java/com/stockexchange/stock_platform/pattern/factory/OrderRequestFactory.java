package com.stockexchange.stock_platform.pattern.factory;

import com.stockexchange.stock_platform.model.enums.OrderSide;
import com.stockexchange.stock_platform.model.enums.OrderType;

import java.math.BigDecimal;

// Creator interface
public interface OrderRequestFactory {
    OrderRequest createOrderRequest(Long userId, String symbol, OrderSide side,
                                    BigDecimal quantity, BigDecimal price);

    OrderType getOrderType();
}