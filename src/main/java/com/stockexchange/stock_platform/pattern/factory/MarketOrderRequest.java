package com.stockexchange.stock_platform.pattern.factory;

import com.stockexchange.stock_platform.model.entity.User;
import com.stockexchange.stock_platform.model.enums.OrderSide;
import com.stockexchange.stock_platform.model.enums.OrderType;
import com.stockexchange.stock_platform.service.StockPriceService;
import com.stockexchange.stock_platform.service.UserService;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class MarketOrderRequest implements OrderRequest {
    private final Long userId;
    private final String symbol;
    private final OrderSide side;
    private final BigDecimal quantity;
    private BigDecimal price; // Determined at execution for market orders

    private final UserService userService;
    private final StockPriceService stockPriceService;

    public MarketOrderRequest(Long userId, String symbol, OrderSide side, BigDecimal quantity,
                              UserService userService, StockPriceService stockPriceService) {
        this.userId = userId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.userService = userService;
        this.stockPriceService = stockPriceService;

        // For market orders, get the current price
        this.price = stockPriceService.getCurrentPrice(symbol).getPrice();
    }

    @Override
    public OrderType getOrderType() {
        return OrderType.MARKET;
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