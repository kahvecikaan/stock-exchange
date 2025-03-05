package com.stockexchange.stock_platform.service;

import com.stockexchange.stock_platform.dto.ChartDataDto;
import java.time.LocalDateTime;

public interface ChartDataService {
    ChartDataDto getStockPriceChart(String symbol, String timeframe);
    ChartDataDto getPortfolioValueChart(Long userId, String timeframe);
    ChartDataDto getComparisonChart(String[] symbols, String timeframe);
}