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
    private BigDecimal price; // Will be lazy-loaded

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

        // Don't fetch the price here -> lazy fetching
    }

    @Override
    public OrderType getOrderType() {
        return OrderType.MARKET;
    }

    @Override
    public BigDecimal getPrice() {
        // Lazy-load the price only when needed
        if (price == null) {
            this.price = stockPriceService.getCurrentPrice(symbol).getPrice();
        }

        return price;
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