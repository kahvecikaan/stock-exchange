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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

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
        return getStockPriceChart(symbol, timeframe, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    @Override
    public ChartDataDto getStockPriceChart(String symbol, String timeframe, ZoneId timezone) {
        List<StockPriceDto> priceData = stockPriceService.getPricesForTimeframe(symbol, timeframe, timezone);
        boolean isIntraday = timeframe.equals("1d") || timeframe.equals("1w");

        // Prepare chart data
        List<String> labels = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        List<Double> volumes = new ArrayList<>();
        List<Double> percentChanges = new ArrayList<>();

        // Sort by timestamp to ensure correct order (oldest to newest)
        priceData.sort(Comparator.comparing(StockPriceDto::getTimestamp));

        for (StockPriceDto price : priceData) {
            // Get the timestamp in the target timezone
            ZonedDateTime zdt = price.getZonedTimestamp();
            if (zdt == null && price.getTimestamp() != null) {
                // Fallback if zonedTimestamp is not set
                zdt = price.getTimestamp().atZone(TimezoneService.DEFAULT_MARKET_TIMEZONE)
                        .withZoneSameInstant(timezone);
            }

            // Format time labels based on timeframe
            String label;
            if (zdt != null) {
                label = isIntraday ? zdt.format(TIME_FORMATTER) : zdt.format(DATE_FORMATTER);
            } else {
                // Fallback if timestamp is not available
                label = "N/A";
            }

            labels.add(label);
            prices.add(price.getPrice().doubleValue());
            volumes.add(price.getVolume() != null ? price.getVolume().doubleValue() : 0.0);

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

        // Build the chart title with timezone information
        String timezoneDisplay = timezone.getId();
        String title = symbol + " Price Chart (" + timeframe + ", " + timezoneDisplay + ")";

        return ChartDataDto.builder()
                .title(title)
                .xAxisLabel("Time")
                .yAxisLabel("Price")
                .labels(labels)
                .datasets(datasets)
                .build();
    }

    @Override
    public ChartDataDto getPortfolioValueChart(Long userId, String timeframe) {
        return getPortfolioValueChart(userId, timeframe, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    @Override
    public ChartDataDto getPortfolioValueChart(Long userId, String timeframe, ZoneId timezone) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = calculateStartTime(timeframe, endTime);

        // Get all holdings
        List<Holding> holdings = holdingRepository.findByUser(user);

        // For each holding, get historical prices in the requested timezone
        Map<LocalDateTime, BigDecimal> dateValueMap = new TreeMap<>();

        for (Holding holding : holdings) {
            List<StockPriceDto> priceData = stockPriceService.getHistoricalPrices(
                    holding.getSymbol(), startTime, endTime, timezone);

            for (StockPriceDto price : priceData) {
                // Convert timestamp to the start of day in the target timezone
                ZonedDateTime zdt = price.getZonedTimestamp();
                if (zdt == null && price.getTimestamp() != null) {
                    zdt = price.getTimestamp().atZone(TimezoneService.DEFAULT_MARKET_TIMEZONE)
                            .withZoneSameInstant(timezone);
                }

                if (zdt != null) {
                    // Use the start of day as key to group data by day
                    LocalDateTime date = zdt.toLocalDate().atStartOfDay();
                    BigDecimal positionValue = price.getPrice().multiply(holding.getQuantity());

                    // Add to total for this date
                    dateValueMap.putIfAbsent(date, BigDecimal.ZERO);
                    dateValueMap.put(date, dateValueMap.get(date).add(positionValue));
                }
            }
        }

        // Prepare chart data
        List<String> labels = new ArrayList<>();
        List<Double> portfolioValues = new ArrayList<>();

        for (Map.Entry<LocalDateTime, BigDecimal> entry : dateValueMap.entrySet()) {
            // Convert LocalDateTime to ZonedDateTime in the target timezone for formatting
            ZonedDateTime zdt = entry.getKey().atZone(timezone);
            labels.add(zdt.format(DATE_FORMATTER));
            portfolioValues.add(entry.getValue().doubleValue());
        }

        // Create datasets
        Map<String, List<Double>> datasets = new HashMap<>();
        datasets.put("Portfolio Value", portfolioValues);

        // Add timezone to chart title
        String timezoneDisplay = timezone.getId();
        String title = "Portfolio Value Over Time (" + timeframe + ", " + timezoneDisplay + ")";

        return ChartDataDto.builder()
                .title(title)
                .xAxisLabel("Date")
                .yAxisLabel("Value")
                .labels(labels)
                .datasets(datasets)
                .build();
    }

    @Override
    public ChartDataDto getComparisonChart(String[] symbols, String timeframe) {
        return getComparisonChart(symbols, timeframe, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    @Override
    public ChartDataDto getComparisonChart(String[] symbols, String timeframe, ZoneId timezone) {
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = calculateStartTime(timeframe, endTime);

        // Get all dates in the range
        Set<LocalDateTime> allDates = new TreeSet<>();
        Map<String, Map<LocalDateTime, BigDecimal>> symbolPriceMap = new HashMap<>();

        // For each symbol, get historical prices in the requested timezone
        for (String symbol : symbols) {
            Map<LocalDateTime, BigDecimal> priceMap = new TreeMap<>();
            List<StockPriceDto> priceData = stockPriceService.getHistoricalPrices(
                    symbol, startTime, endTime, timezone);

            for (StockPriceDto price : priceData) {
                // Convert timestamp to the start of day in the target timezone
                ZonedDateTime zdt = price.getZonedTimestamp();
                if (zdt == null && price.getTimestamp() != null) {
                    zdt = price.getTimestamp().atZone(TimezoneService.DEFAULT_MARKET_TIMEZONE)
                            .withZoneSameInstant(timezone);
                }

                if (zdt != null) {
                    // Use the start of day as key
                    LocalDateTime date = zdt.toLocalDate().atStartOfDay();
                    priceMap.put(date, price.getPrice());
                    allDates.add(date);
                }
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
            // Convert to ZonedDateTime for formatting
            ZonedDateTime zdt = date.atZone(timezone);
            labels.add(zdt.format(DATE_FORMATTER));

            for (String symbol : symbols) {
                Map<LocalDateTime, BigDecimal> priceMap = symbolPriceMap.get(symbol);
                BigDecimal price = priceMap.getOrDefault(date, null);

                if (price != null) {
                    datasets.get(symbol).add(price.doubleValue());
                } else {
                    // Use previous value or null if no price available
                    List<Double> symbolData = datasets.get(symbol);
                    Double prevValue = symbolData.isEmpty() ? null : symbolData.getLast();
                    datasets.get(symbol).add(prevValue);
                }
            }
        }

        // Add timezone to chart title
        String timezoneDisplay = timezone.getId();
        String title = "Stock Price Comparison (" + timeframe + ", " + timezoneDisplay + ")";

        return ChartDataDto.builder()
                .title(title)
                .xAxisLabel("Date")
                .yAxisLabel("Price")
                .labels(labels)
                .datasets(datasets)
                .build();
    }

    /**
     * Calculates the start time based on the given timeframe parameter.
     * @param timeframe String representation of the timeframe (1d, 1w, 1m, 3m, 1y)
     * @param endTime The end time from which to calculate the start time
     * @return The calculated start time
     */
    private LocalDateTime calculateStartTime(String timeframe, LocalDateTime endTime) {
        return switch (timeframe.toLowerCase()) {
            case "1d" -> endTime.minusDays(1);
            case "1w" -> endTime.minusDays(7);
            case "1m" -> endTime.minusMonths(1);
            case "3m" -> endTime.minusMonths(3);
            case "1y" -> endTime.minusYears(1);
            default -> endTime.minusMonths(1); // Default to 1 month
        };
    }
}