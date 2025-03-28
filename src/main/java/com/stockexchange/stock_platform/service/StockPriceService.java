package com.stockexchange.stock_platform.service;

import com.stockexchange.stock_platform.dto.SearchResultDto;
import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.model.entity.StockPrice;

import java.time.LocalDateTime;
import java.time.ZoneId;
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

    // New timezone-aware methods - as extensions
    StockPriceDto getCurrentPrice(String symbol, ZoneId timezone);
    List<StockPriceDto> getHistoricalPrices(String symbol, LocalDateTime startTime, LocalDateTime endTime, ZoneId timezone);
    List<StockPriceDto> getIntradayPrices(String symbol, String interval, ZoneId timezone);
    List<StockPriceDto> getWeeklyPrices(String symbol, int weeks, ZoneId timezone);
    List<StockPriceDto> getMonthlyPrices(String symbol, int months, ZoneId timezone);
    List<StockPriceDto> getPricesForTimeframe(String symbol, String timeframe, ZoneId timezone);
}