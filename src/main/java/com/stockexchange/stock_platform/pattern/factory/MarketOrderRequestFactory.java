package com.stockexchange.stock_platform.pattern.factory;

import com.stockexchange.stock_platform.model.enums.OrderSide;
import com.stockexchange.stock_platform.service.StockPriceService;
import com.stockexchange.stock_platform.service.UserService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class MarketOrderRequestFactory implements OrderRequestFactory {

    private final UserService userService;
    private final StockPriceService stockPriceService;

    public MarketOrderRequestFactory(UserService userService, StockPriceService stockPriceService) {
        this.userService = userService;
        this.stockPriceService = stockPriceService;
    }

    @Override
    public OrderRequest createOrderRequest(Long userId, String symbol, OrderSide side,
                                           BigDecimal quantity, BigDecimal price) {
        // For market orders, price parameter is ignored
        return new MarketOrderRequest(userId, symbol, side, quantity, userService, stockPriceService);
    }
}