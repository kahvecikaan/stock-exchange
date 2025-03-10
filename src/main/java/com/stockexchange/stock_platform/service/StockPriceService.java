package com.stockexchange.stock_platform.service;

import com.stockexchange.stock_platform.dto.SearchResultDto;
import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.model.entity.StockPrice;

import java.time.LocalDateTime;
import java.util.List;

public interface StockPriceService {
    StockPriceDto getCurrentPrice(String symbol);
    List<StockPriceDto> getHistoricalPrices(String symbol, LocalDateTime startTime, LocalDateTime endTime);
    List<StockPriceDto> getIntradayPrices(String symbol, String interval);
    List<StockPriceDto> getWeeklyPrices(String symbol, int weeks);
    List<StockPriceDto> getMonthlyPrices(String symbol, int months);
    List<StockPriceDto> getPricesForTimeframe(String symbol, String timeframe);
    List<SearchResultDto> searchStocks(String keywords);
    void saveStockPrice(StockPrice stockPrice);
}