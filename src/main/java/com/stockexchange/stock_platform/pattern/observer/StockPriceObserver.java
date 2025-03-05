package com.stockexchange.stock_platform.pattern.observer;

import com.stockexchange.stock_platform.dto.StockPriceDto;

public interface StockPriceObserver {
    void update(StockPriceDto stockPrice);
}