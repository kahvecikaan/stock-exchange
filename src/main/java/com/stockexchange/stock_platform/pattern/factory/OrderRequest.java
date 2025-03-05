package com.stockexchange.stock_platform.pattern.factory;

import com.stockexchange.stock_platform.model.enums.OrderSide;
import com.stockexchange.stock_platform.model.enums.OrderType;

import java.math.BigDecimal;

// Product interface
public interface OrderRequest {
    Long getUserId();
    String getSymbol();
    OrderType getOrderType();
    OrderSide getSide();
    BigDecimal getQuantity();
    BigDecimal getPrice();
    boolean validate();
}