package com.stockexchange.stock_platform.service.impl;

import com.stockexchange.stock_platform.dto.ChartDataDto;
import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.model.entity.Holding;
import com.stockexchange.stock_platform.model.entity.User;
import com.stockexchange.stock_platform.repository.HoldingRepository;
import com.stockexchange.stock_platform.repository.UserRepository;
import com.stockexchange.stock_platform.service.ChartDataService;
import com.stockexchange.stock_platform.service.StockPriceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChartDataServiceImpl implements ChartDataService {

    private final StockPriceService stockPriceService;
    private final UserRepository userRepository;
    private final HoldingRepository holdingRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public ChartDataServiceImpl(StockPriceService stockPriceService,
                                UserRepository userRepository,
                                HoldingRepository holdingRepository) {
        this.stockPriceService = stockPriceService;
        this.userRepository = userRepository;
        this.holdingRepository = holdingRepository;
    }

    @Override
    public ChartDataDto getStockPriceChart(String symbol, String timeframe) {
        List<StockPriceDto> priceData = stockPriceService.getPricesForTimeframe(symbol, timeframe);

        boolean isIntraday = timeframe.equals("1d") || timeframe.equals("1w");

        // Prepare chart data
        List<String> labels = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        List<Double> volumes = new ArrayList<>();
        List<Double> percentChanges = new ArrayList<>();

        // Use reversed order for chart data (oldest to newest)
        for (int i = priceData.size() - 1; i >= 0; i--) {
            StockPriceDto price = priceData.get(i);

            // Format time labels based on timeframe
            String label = isIntraday ?
                    price.getTimestamp().format(TIME_FORMATTER) :
                    price.getTimestamp().format(DATE_FORMATTER);
            labels.add(label);
            prices.add(price.getPrice().doubleValue());
            volumes.add(price.getVolume() !=  null ? price.getVolume().doubleValue() : 0.0);

            // Add percentage change as a dataset
            if (price.getChangePercent() != null) {
                percentChanges.add(price.getChangePercent().doubleValue());
            } else {
                percentChanges.add(0.0);
            }
        }

        // Create datasets
        Map<String, List<Double>> datasets = new HashMap<>();
        datasets.put("Price", prices);
        datasets.put("Volume", volumes);
        datasets.put("PercentChange", percentChanges);

        return ChartDataDto.builder()
                .title(symbol + " Price Chart")
                .xAxisLabel("Time")
                .yAxisLabel("Price")
                .labels(labels)
                .datasets(datasets)
                .build();
    }

    @Override
    public ChartDataDto getPortfolioValueChart(Long userId, String timeframe) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime;

        // Determine time range based on timeframe parameter
        switch (timeframe.toLowerCase()) {
            case "1w":
                startTime = endTime.minusDays(7);
                break;
            case "1m":
                startTime = endTime.minusMonths(1);
                break;
            case "3m":
                startTime = endTime.minusMonths(3);
                break;
            case "1y":
                startTime = endTime.minusYears(1);
                break;
            default:
                startTime = endTime.minusMonths(1); // Default to 1 month
                break;
        }

        // Get all holdings
        List<Holding> holdings = holdingRepository.findByUser(user);

        // For each holding, get historical prices
        Map<LocalDateTime, BigDecimal> dateValueMap = new TreeMap<>();

        for (Holding holding : holdings) {
            List<StockPriceDto> priceData = stockPriceService.getHistoricalPrices(
                    holding.getSymbol(), startTime, endTime);

            for (StockPriceDto price : priceData) {
                LocalDateTime date = price.getTimestamp().toLocalDate().atStartOfDay();
                BigDecimal positionValue = price.getPrice().multiply(holding.getQuantity());

                // Add to total for this date
                dateValueMap.putIfAbsent(date, BigDecimal.ZERO);
                dateValueMap.put(date, dateValueMap.get(date).add(positionValue));
            }
        }

        // Prepare chart data
        List<String> labels = new ArrayList<>();
        List<Double> portfolioValues = new ArrayList<>();

        for (Map.Entry<LocalDateTime, BigDecimal> entry : dateValueMap.entrySet()) {
            labels.add(entry.getKey().format(DATE_FORMATTER));
            portfolioValues.add(entry.getValue().doubleValue());
        }

        // Create datasets
        Map<String, List<Double>> datasets = new HashMap<>();
        datasets.put("Portfolio Value", portfolioValues);

        return ChartDataDto.builder()
                .title("Portfolio Value Over Time")
                .xAxisLabel("Date")
                .yAxisLabel("Value")
                .labels(labels)
                .datasets(datasets)
                .build();
    }

    @Override
    public ChartDataDto getComparisonChart(String[] symbols, String timeframe) {
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime;

        // Determine time range based on timeframe parameter
        switch (timeframe.toLowerCase()) {
            case "1w":
                startTime = endTime.minusDays(7);
                break;
            case "1m":
                startTime = endTime.minusMonths(1);
                break;
            case "3m":
                startTime = endTime.minusMonths(3);
                break;
            case "1y":
                startTime = endTime.minusYears(1);
                break;
            default:
                startTime = endTime.minusMonths(1); // Default to 1 month
                break;
        }

        // Get all dates in the range
        Set<LocalDateTime> allDates = new TreeSet<>();
        Map<String, Map<LocalDateTime, BigDecimal>> symbolPriceMap = new HashMap<>();

        // For each symbol, get historical prices
        for (String symbol : symbols) {
            Map<LocalDateTime, BigDecimal> priceMap = new TreeMap<>();
            List<StockPriceDto> priceData = stockPriceService.getHistoricalPrices(
                    symbol, startTime, endTime);

            for (StockPriceDto price : priceData) {
                LocalDateTime date = price.getTimestamp().toLocalDate().atStartOfDay();
                priceMap.put(date, price.getPrice());
                allDates.add(date);
            }

            symbolPriceMap.put(symbol, priceMap);
        }

        // Prepare chart data
        List<String> labels = new ArrayList<>();
        Map<String, List<Double>> datasets = new HashMap<>();

        // Initialize datasets for each symbol
        for (String symbol : symbols) {
            datasets.put(symbol, new ArrayList<>());
        }

        // Fill in data points for each date
        for (LocalDateTime date : allDates) {
            labels.add(date.format(DATE_FORMATTER));

            for (String symbol : symbols) {
                Map<LocalDateTime, BigDecimal> priceMap = symbolPriceMap.get(symbol);
                BigDecimal price = priceMap.getOrDefault(date, null);

                if (price != null) {
                    datasets.get(symbol).add(price.doubleValue());
                } else {
                    // Use previous value or null if no price available
                    List<Double> symbolData = datasets.get(symbol);
                    Double prevValue = symbolData.isEmpty() ? null : symbolData.get(symbolData.size() - 1);
                    datasets.get(symbol).add(prevValue);
                }
            }
        }

        return ChartDataDto.builder()
                .title("Stock Price Comparison")
                .xAxisLabel("Date")
                .yAxisLabel("Price")
                .labels(labels)
                .datasets(datasets)
                .build();
    }
}