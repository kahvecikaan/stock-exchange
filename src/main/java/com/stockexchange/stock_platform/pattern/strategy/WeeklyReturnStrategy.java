package com.stockexchange.stock_platform.pattern.strategy;

import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.model.entity.Holding;
import com.stockexchange.stock_platform.model.entity.User;
import com.stockexchange.stock_platform.service.StockPriceService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class WeeklyReturnStrategy implements PortfolioAnalysisStrategy {

    private final StockPriceService stockPriceService;

    public WeeklyReturnStrategy(StockPriceService stockPriceService) {
        this.stockPriceService = stockPriceService;
    }

    @Override
    public Map<String, Object> analyze(User user) {
        Map<String, Object> result = new HashMap<>();

        // Define time range for weekly analysis
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime weekAgo = now.minusDays(7);

        // Track totals
        BigDecimal totalCurrentValue = BigDecimal.ZERO;
        BigDecimal totalValueWeekAgo = BigDecimal.ZERO;

        // Analyze each holding
        for (Holding holding : user.getHoldings()) {
            // Get current price data
            StockPriceDto currentPrice = stockPriceService.getCurrentPrice(holding.getSymbol());

            // Get historical price data
            List<StockPriceDto> historicalPrices = stockPriceService.getHistoricalPrices(
                    holding.getSymbol(), weekAgo, now);

            // Find price from a week ago (or closest available)
            StockPriceDto weekAgoPrice = findClosestPriceData(historicalPrices, weekAgo);

            // Calculate values
            BigDecimal currentValue = holding.getQuantity().multiply(currentPrice.getPrice());
            BigDecimal investmentValue = holding.getQuantity().multiply(holding.getAvgPrice());

            // Calculate value from a week ago
            BigDecimal valueWeekAgo;
            if (weekAgoPrice != null) {
                valueWeekAgo = holding.getQuantity().multiply(weekAgoPrice.getPrice());
            } else {
                // If no historical data, use investment value as fallback
                valueWeekAgo = investmentValue;
            }

            // Calculate weekly change
            BigDecimal weeklyChange = currentValue.subtract(valueWeekAgo);
            BigDecimal weeklyChangePercent = valueWeekAgo.compareTo(BigDecimal.ZERO) > 0 ?
                    weeklyChange.multiply(new BigDecimal("100")).divide(valueWeekAgo, 2, RoundingMode.HALF_UP) :
                    BigDecimal.ZERO;

            // Update totals
            totalCurrentValue = totalCurrentValue.add(currentValue);
            totalValueWeekAgo = totalValueWeekAgo.add(valueWeekAgo);

            // Store detailed info for this holding
            Map<String, Object> holdingData = new HashMap<>();
            holdingData.put("symbol", holding.getSymbol());
            holdingData.put("quantity", holding.getQuantity());
            holdingData.put("avgPrice", holding.getAvgPrice());
            holdingData.put("currentPrice", currentPrice.getPrice());
            holdingData.put("currentValue", currentValue);
            holdingData.put("valueWeekAgo", valueWeekAgo);
            holdingData.put("weeklyChange", weeklyChange);
            holdingData.put("weeklyChangePercent", weeklyChangePercent);

            result.put(holding.getSymbol(), holdingData);
        }

        // Calculate portfolio-level metrics
        BigDecimal totalWeeklyChange = totalCurrentValue.subtract(totalValueWeekAgo);
        BigDecimal totalWeeklyChangePercent = totalValueWeekAgo.compareTo(BigDecimal.ZERO) > 0 ?
                totalWeeklyChange.multiply(new BigDecimal("100")).divide(totalValueWeekAgo, 2, RoundingMode.HALF_UP) :
                BigDecimal.ZERO;

        // Add summary data
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalCurrentValue", totalCurrentValue);
        summary.put("totalValueWeekAgo", totalValueWeekAgo);
        summary.put("totalWeeklyChange", totalWeeklyChange);
        summary.put("totalWeeklyChangePercent", totalWeeklyChangePercent);
        summary.put("cashBalance", user.getCashBalance());
        summary.put("totalAssets", totalCurrentValue.add(user.getCashBalance()));

        result.put("summary", summary);
        return result;
    }

    private StockPriceDto findClosestPriceData(List<StockPriceDto> prices, LocalDateTime targetTime) {
        if (prices == null || prices.isEmpty()) {
            return null;
        }

        // Find the price data closest to the target time
        StockPriceDto closest = prices.get(0);
        long closestDifference = Math.abs(
                targetTime.toEpochSecond(java.time.ZoneOffset.UTC) -
                        closest.getTimestamp().toEpochSecond(java.time.ZoneOffset.UTC));

        for (int i = 1; i < prices.size(); i++) {
            StockPriceDto current = prices.get(i);
            long currentDifference = Math.abs(
                    targetTime.toEpochSecond(java.time.ZoneOffset.UTC) -
                            current.getTimestamp().toEpochSecond(java.time.ZoneOffset.UTC));

            if (currentDifference < closestDifference) {
                closest = current;
                closestDifference = currentDifference;
            }
        }

        return closest;
    }

    @Override
    public String getName() {
        return "weekly";
    }
}