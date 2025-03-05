package com.stockexchange.stock_platform.pattern.observer;

import com.stockexchange.stock_platform.dto.StockPriceDto;

public interface StockPriceSubject {
    void registerObserver(StockPriceObserver observer);
    void removeObserver(StockPriceObserver observer);
    void notifyObservers(StockPriceDto stockPrice);
}