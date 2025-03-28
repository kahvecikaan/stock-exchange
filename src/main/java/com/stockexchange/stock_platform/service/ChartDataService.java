package com.stockexchange.stock_platform.service;

import com.stockexchange.stock_platform.dto.ChartDataDto;

import java.time.ZoneId;

public interface ChartDataService {

    /**
     * Get stock price chart data
     * @param symbol The stock symbol
     * @param timeframe The timeframe (e.g., "1d", "1w", "1m", "3m", "1y")
     * @return ChartDataDto with price data
     */
    ChartDataDto getStockPriceChart(String symbol, String timeframe);

    /**
     * Get stock price chart data with timezone specification
     * @param symbol The stock symbol
     * @param timeframe The timeframe (e.g., "1d", "1w", "1m", "3m", "1y")
     * @param timezone The target timezone
     * @return ChartDataDto with price data in specified timezone
     */
    ChartDataDto getStockPriceChart(String symbol, String timeframe, ZoneId timezone);

    /**
     * Get portfolio value chart data
     * @param userId The user ID
     * @param timeframe The timeframe (e.g., "1w", "1m", "3m", "1y")
     * @return ChartDataDto with portfolio value data
     */
    ChartDataDto getPortfolioValueChart(Long userId, String timeframe);

    /**
     * Get portfolio value chart data with timezone specification
     * @param userId The user ID
     * @param timeframe The timeframe (e.g., "1w", "1m", "3m", "1y")
     * @param timezone The target timezone
     * @return ChartDataDto with portfolio value data in specified timezone
     */
    ChartDataDto getPortfolioValueChart(Long userId, String timeframe, ZoneId timezone);

    /**
     * Get comparison chart data for multiple symbols
     * @param symbols Array of stock symbols
     * @param timeframe The timeframe (e.g., "1w", "1m", "3m", "1y")
     * @return ChartDataDto with comparison data
     */
    ChartDataDto getComparisonChart(String[] symbols, String timeframe);

    /**
     * Get comparison chart data for multiple symbols with timezone specification
     * @param symbols Array of stock symbols
     * @param timeframe The timeframe (e.g., "1w", "1m", "3m", "1y")
     * @param timezone The target timezone
     * @return ChartDataDto with comparison data in specified timezone
     */
    ChartDataDto getComparisonChart(String[] symbols, String timeframe, ZoneId timezone);
}