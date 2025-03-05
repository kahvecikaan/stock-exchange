package com.stockexchange.stock_platform.pattern.strategy;

import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.model.entity.Holding;
import com.stockexchange.stock_platform.model.entity.User;
import com.stockexchange.stock_platform.service.StockPriceService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@Component
public class DailyReturnStrategy implements PortfolioAnalysisStrategy {

    private final StockPriceService stockPriceService;

    public DailyReturnStrategy(StockPriceService stockPriceService) {
        this.stockPriceService = stockPriceService;
    }

    @Override
    public Map<String, Object> analyze(User user) {
        Map<String, Object> result = new HashMap<>();

        // Track totals
        BigDecimal totalInvestment = BigDecimal.ZERO;
        BigDecimal totalCurrentValue = BigDecimal.ZERO;
        BigDecimal totalDailyChange = BigDecimal.ZERO;

        // Analyze each holding
        for (Holding holding : user.getHoldings()) {
            StockPriceDto priceData = stockPriceService.getCurrentPrice(holding.getSymbol());

            BigDecimal investmentValue = holding.getQuantity().multiply(holding.getAvgPrice());
            BigDecimal currentValue = holding.getQuantity().multiply(priceData.getPrice());
            BigDecimal dailyChange = priceData.getChange() != null ?
                    priceData.getChange().multiply(holding.getQuantity()) : BigDecimal.ZERO;

            totalInvestment = totalInvestment.add(investmentValue);
            totalCurrentValue = totalCurrentValue.add(currentValue);
            totalDailyChange = totalDailyChange.add(dailyChange);

            // Store detailed info for each holding
            Map<String, Object> holdingData = new HashMap<>();
            holdingData.put("symbol", holding.getSymbol());
            holdingData.put("quantity", holding.getQuantity());
            holdingData.put("avgPrice", holding.getAvgPrice());
            holdingData.put("currentPrice", priceData.getPrice());
            holdingData.put("investmentValue", investmentValue);
            holdingData.put("currentValue", currentValue);
            holdingData.put("dailyChange", dailyChange);
            holdingData.put("dailyChangePercent", priceData.getChangePercent());

            result.put(holding.getSymbol(), holdingData);
        }

        // Add summary data
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalInvestment", totalInvestment);
        summary.put("totalCurrentValue", totalCurrentValue);
        summary.put("totalProfit", totalCurrentValue.subtract(totalInvestment));

        // Calculate total return percentage
        if (totalInvestment.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal totalReturnPercent = totalCurrentValue.subtract(totalInvestment)
                    .multiply(new BigDecimal("100"))
                    .divide(totalInvestment, 2, RoundingMode.HALF_UP);
            summary.put("totalReturnPercent", totalReturnPercent);
        } else {
            summary.put("totalReturnPercent", BigDecimal.ZERO);
        }

        summary.put("totalDailyChange", totalDailyChange);

        // Calculate daily return percentage on total portfolio
        if (totalCurrentValue.subtract(totalDailyChange).compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal dailyReturnPercent = totalDailyChange
                    .multiply(new BigDecimal("100"))
                    .divide(totalCurrentValue.subtract(totalDailyChange), 2, RoundingMode.HALF_UP);
            summary.put("dailyReturnPercent", dailyReturnPercent);
        } else {
            summary.put("dailyReturnPercent", BigDecimal.ZERO);
        }

        result.put("summary", summary);
        return result;
    }

    @Override
    public String getName() {
        return "daily";
    }
}