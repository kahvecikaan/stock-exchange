package com.stockexchange.stock_platform.dto;

import com.stockexchange.stock_platform.model.enums.OrderSide;
import com.stockexchange.stock_platform.model.enums.OrderType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PlaceOrderRequestDto {
    @NotNull(message = "User ID is required")
    private Long userId;

    @NotNull(message = "Symbol is required")
    private String symbol;

    @NotNull(message = "Order type is required")
    private OrderType orderType;

    @NotNull(message = "Order type is required")
    private OrderSide side;

    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    private BigDecimal quantity;

    // Price is optional for market orders, but required for limit orders
    private BigDecimal price;
}
