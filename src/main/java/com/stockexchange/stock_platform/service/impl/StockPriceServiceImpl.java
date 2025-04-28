package com.stockexchange.stock_platform.service.impl;

import com.stockexchange.stock_platform.dto.SearchResultDto;
import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.model.entity.StockPrice;
import com.stockexchange.stock_platform.pattern.observer.StockPriceObserver;
import com.stockexchange.stock_platform.pattern.observer.StockPriceSubject;
import com.stockexchange.stock_platform.repository.StockPriceRepository;
import com.stockexchange.stock_platform.service.StockPriceService;
import com.stockexchange.stock_platform.service.api.AlpacaClient;
import com.stockexchange.stock_platform.service.api.AlpacaWebSocketClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class StockPriceServiceImpl implements StockPriceService, StockPriceSubject, StockPriceObserver {

    private final AlpacaClient alpacaClient;
    private final AlpacaWebSocketClient webSocketClient;
    private final StockPriceRepository stockPriceRepository;
    private final TimezoneService timezoneService;

    // Observers that will be notified of price updates
    private final List<StockPriceObserver> observers = new ArrayList<>();

    // Set of actively tracked symbols
    private final Set<String> activeSymbols = ConcurrentHashMap.newKeySet();

    // Cache for real-time prices from WebSocket
    private final Map<String, StockPriceDto> realtimePrices = new ConcurrentHashMap<>();

    public StockPriceServiceImpl(AlpacaClient alpacaClient,
                                 AlpacaWebSocketClient webSocketClient,
                                 StockPriceRepository stockPriceRepository,
                                 TimezoneService timezoneService) {
        this.alpacaClient = alpacaClient;
        this.webSocketClient = webSocketClient;
        this.stockPriceRepository = stockPriceRepository;
        this.timezoneService = timezoneService;
    }

    @PostConstruct
    public void init() {
        // Register as an observer of the WebSocket client to receive real-time updates
        webSocketClient.registerObserver(this);
        log.info("StockPriceService registered as observer of WebSocket client");
    }

    /**
     * Register a symbol for automatic tracking in real-time
     * @param symbol The stock symbol to track
     */
    public void registerSymbolForTracking(String symbol) {
        if (symbol != null && !symbol.isBlank()) {
            symbol = symbol.toUpperCase();
            activeSymbols.add(symbol);

            // Also subscribe to WebSocket updates for this symbol
            webSocketClient.subscribeToSymbol(symbol);

            log.debug("Registered symbol for tracking: {}", symbol);
        }
    }

    /**
     * Handles real-time price updates from WebSocket
     * This method is called when the WebSocket client receives a price update
     */
    @Override
    @CacheEvict(value = {"stockPrices_1d", "stockPrices_1w"}, key = "#stockPrice.symbol", condition = "#stockPrice != null")
    public void update(StockPriceDto stockPrice) {
        if (stockPrice == null || stockPrice.getSymbol() == null) {
            return;
        }

        String symbol = stockPrice.getSymbol().toUpperCase();

        // Update our real-time price cache
        realtimePrices.put(symbol, stockPrice);

        // Save to database for historical record
        saveStockPriceFromDto(stockPrice);

        // Notify our own observers of the price update
        notifyObservers(stockPrice);

        log.debug("Received real-time update for {}: {}", symbol, stockPrice.getPrice());
    }

    @Override
    public StockPriceDto getCurrentPrice(String symbol) {
        return getCurrentPrice(symbol, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    @Override
    @Cacheable(value = "currentPrices", key = "#symbol + '-' + #userTimezone.id", unless = "#result == null")
    public StockPriceDto getCurrentPrice(String symbol, ZoneId userTimezone) {
        symbol = symbol.toUpperCase();

        // Register this symbol for real-time tracking
        registerSymbolForTracking(symbol);

        // First check if we have a real-time price from WebSocket
        StockPriceDto realtimePrice = realtimePrices.get(symbol);

        if (realtimePrice != null) {
            log.debug("Using real-time price for {}: {}", symbol, realtimePrice.getPrice());

            // Convert to the user's timezone if needed
            if (userTimezone != null && realtimePrice.getSourceTimezone() != null &&
                    !userTimezone.equals(realtimePrice.getSourceTimezone())) {

                return convertToUserTimezone(realtimePrice, userTimezone);
            }

            return realtimePrice;
        }

        // If no real-time price available, get from REST API
        log.debug("No real-time price available for {}, using REST API", symbol);
        StockPriceDto stockPrice = alpacaClient.getCurrentPrice(symbol);

        // Convert to user's timezone if needed
        if (userTimezone != null && stockPrice.getSourceTimezone() != null &&
                !userTimezone.equals(stockPrice.getSourceTimezone())) {

            stockPrice = convertToUserTimezone(stockPrice, userTimezone);
        }

        // Save to database and notify observers
        saveStockPriceFromDto(stockPrice);
        notifyObservers(stockPrice);

        return stockPrice;
    }

    @Override
    public List<StockPriceDto> getHistoricalPrices(String symbol, LocalDateTime startTime, LocalDateTime endTime) {
        return getHistoricalPrices(symbol, startTime, endTime, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    @Override
    public List<StockPriceDto> getHistoricalPrices(String symbol, LocalDateTime startTime, LocalDateTime endTime, ZoneId userTimezone) {
        symbol = symbol.toUpperCase();

        // Register for real-time updates
        registerSymbolForTracking(symbol);

        // Check if we have data in our database first
        List<StockPrice> dbPrices = stockPriceRepository.findBySymbolAndTimeBetweenOrderByTimeAsc(
                symbol, startTime, endTime);

        // Determine appropriate timeframe for Alpaca API
        String timeframe = determineTimeframeForDateRange(startTime, endTime);

        List<StockPriceDto> prices;

        // If not enough data in database, fetch from API
        if (dbPrices.size() < 10) { // Arbitrary threshold to determine if we need more data
            prices = alpacaClient.getHistoricalBars(symbol, timeframe, startTime, endTime);

            // Save to database for future use
            for (StockPriceDto price : prices) {
                saveStockPriceFromDto(price);
            }
        } else {
            // Convert database entities to DTOs
            prices = dbPrices.stream()
                    .map(this::convertToDto)
                    .collect(Collectors.toList());

            // Convert to user timezone if needed
            if (userTimezone != null) {
                prices = prices.stream()
                        .map(price -> convertToUserTimezone(price, userTimezone))
                        .collect(Collectors.toList());
            }

            // Recalculate changes since these come from DB
            calculateChangesFromReference(prices);
        }

        return prices;
    }

    @Override
    public List<StockPriceDto> getIntradayPrices(String symbol, String interval) {
        return getIntradayPrices(symbol, interval, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    @Override
    public List<StockPriceDto> getIntradayPrices(String symbol, String interval, ZoneId userTimezone) {
        symbol = symbol.toUpperCase();

        // Register for real-time updates
        registerSymbolForTracking(symbol);

        // Convert interval to Alpaca format
        String alpacaInterval = convertToAlpacaTimeframe(interval);

        // Get today's date for the timeframe
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.toLocalDate().atStartOfDay();

        // Use historical bars with intraday timeframe
        List<StockPriceDto> prices = alpacaClient.getHistoricalBars(
                symbol, alpacaInterval, startTime, endTime);

        // Save to database
        for (StockPriceDto price : prices) {
            saveStockPriceFromDto(price);
        }

        // Add the most recent real-time price if available
        StockPriceDto realtimePrice = realtimePrices.get(symbol);
        if (realtimePrice != null) {
            // Convert to user timezone if needed
            if (userTimezone != null && !userTimezone.equals(realtimePrice.getSourceTimezone())) {
                realtimePrice = convertToUserTimezone(realtimePrice, userTimezone);
            }

            // Check if we should add/update with the real-time price
            if (!prices.isEmpty()) {
                // Get the most recent price
                StockPriceDto mostRecent = prices.getLast();

                // Only add if real-time price is more recent
                if (realtimePrice.getTimestamp().isAfter(mostRecent.getTimestamp())) {
                    // Calculate change vs. first price point for consistency
                    if (!prices.isEmpty()) {
                        calculateChangeForRealTimePrice(prices, realtimePrice);
                    }

                    prices.add(realtimePrice);
                }
            } else {
                // If no other prices, just add the real-time price
                prices.add(realtimePrice);
            }
        }

        return prices;
    }

    @Override
    public List<StockPriceDto> getWeeklyPrices(String symbol, int weeks) {
        return getWeeklyPrices(symbol, weeks, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    @Override
    public List<StockPriceDto> getWeeklyPrices(String symbol, int weeks, ZoneId userTimezone) {
        symbol = symbol.toUpperCase();

        // Register for real-time updates
        registerSymbolForTracking(symbol);

        // Calculate date range
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusWeeks(weeks);

        // Get data with daily timeframe from Alpaca
        List<StockPriceDto> prices = alpacaClient.getHistoricalBars(
                symbol, "1Day", startTime, endTime);

        // Group by week to get weekly data
        Map<LocalDateTime, List<StockPriceDto>> weeklyData = new HashMap<>();
        for (StockPriceDto price : prices) {
            // Group by the start of the week
            LocalDateTime weekStart = price.getTimestamp().toLocalDate()
                    .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                    .atStartOfDay();

            weeklyData.computeIfAbsent(weekStart, k -> new ArrayList<>()).add(price);
        }

        // For each week, take the last day's data
        List<StockPriceDto> weeklyPrices = new ArrayList<>();
        for (List<StockPriceDto> weekPrices : weeklyData.values()) {
            weekPrices.sort(Comparator.comparing(StockPriceDto::getTimestamp));
            if (!weekPrices.isEmpty()) {
                weeklyPrices.add(weekPrices.getLast());
            }
        }

        // Sort by date
        weeklyPrices.sort(Comparator.comparing(StockPriceDto::getTimestamp));

        // Calculate changes
        calculateChangesFromReference(weeklyPrices);

        return weeklyPrices;
    }

    @Override
    public List<StockPriceDto> getMonthlyPrices(String symbol, int months) {
        return getMonthlyPrices(symbol, months, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    @Override
    public List<StockPriceDto> getMonthlyPrices(String symbol, int months, ZoneId userTimezone) {
        symbol = symbol.toUpperCase();

        // Register for real-time updates
        registerSymbolForTracking(symbol);

        // Calculate date range
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusMonths(months);

        // Get data with weekly timeframe from Alpaca for better performance
        List<StockPriceDto> prices = alpacaClient.getHistoricalBars(
                symbol, "1Week", startTime, endTime);

        // Group by month to get monthly data
        Map<String, List<StockPriceDto>> monthlyData = new HashMap<>();
        for (StockPriceDto price : prices) {
            // Group by year-month
            String yearMonth = price.getTimestamp().getYear() + "-" +
                    String.format("%02d", price.getTimestamp().getMonthValue());

            monthlyData.computeIfAbsent(yearMonth, k -> new ArrayList<>()).add(price);
        }

        // For each month, take the last day's data
        List<StockPriceDto> monthlyPrices = new ArrayList<>();
        for (List<StockPriceDto> monthPrices : monthlyData.values()) {
            monthPrices.sort(Comparator.comparing(StockPriceDto::getTimestamp));
            if (!monthPrices.isEmpty()) {
                monthlyPrices.add(monthPrices.getLast());
            }
        }

        // Sort by date
        monthlyPrices.sort(Comparator.comparing(StockPriceDto::getTimestamp));

        // Calculate changes
        calculateChangesFromReference(monthlyPrices);

        return monthlyPrices;
    }

    @Override
    public List<StockPriceDto> getPricesForTimeframe(String symbol, String timeframe) {
        return getPricesForTimeframe(symbol, timeframe, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    /**
     * Uses cache for timeframe data with cache key that includes the symbol, timeframe and timezone
     */
    @Override
    @Cacheable(
            cacheResolver = "stockPriceCacheResolver",
            key           = "#symbol.toUpperCase() + '-' + #userTimezone.id",
            unless        = "#result == null || #result.isEmpty()"
    )
    public List<StockPriceDto> getPricesForTimeframe(String symbol, String timeframe, ZoneId userTimezone) {
        log.debug("CACHE MISS: computing prices for {} [{}] in {}", symbol, timeframe, userTimezone);

        symbol = symbol.toUpperCase();

        // Register for real-time updates
        registerSymbolForTracking(symbol);

        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime;
        String alpacaTimeframe;

        // Determine the timeframe parameters based on requirements
        switch (timeframe.toLowerCase()) {
            case "1d":
                startTime = endTime.minusDays(1);
                alpacaTimeframe = "5Min"; // 5-minute intervals for 1-day chart
                break;
            case "1w":
                startTime = endTime.minusWeeks(1);
                alpacaTimeframe = "30Min"; // 30-minute intervals for 1-week chart
                break;
            case "1m":
                startTime = endTime.minusMonths(1);
                alpacaTimeframe = "2Hour"; // 2-hour intervals for 1-month chart
                break;
            case "3m":
                startTime = endTime.minusMonths(3);
                alpacaTimeframe = "12Hour"; // 12-hour intervals for 3-month chart
                break;
            case "1y":
                startTime = endTime.minusYears(1);
                alpacaTimeframe = "1Day"; // 1-day intervals for 1-year chart
                break;
            case "5y":
                // For 5-year data, use a date 3 days before current date to ensure we can use "sip" feed
                endTime = LocalDateTime.now().minusDays(3);
                startTime = endTime.minusYears(5);
                alpacaTimeframe = "1Week"; // 1-week intervals for 5-year chart
                break;
            default:
                log.warn("Unsupported timeframe: {}, defaulting to 1m", timeframe);
                startTime = endTime.minusMonths(1);
                alpacaTimeframe = "2Hour"; // Default to 1-month view
        }

        // Get price data from Alpaca
        List<StockPriceDto> prices = alpacaClient.getHistoricalBars(
                symbol, alpacaTimeframe, startTime, endTime);

        // Filter out pre-market data that falls outside regular market hours
        // This is especially needed for the 1w timeframe where Alpaca returns 13:00 UTC (9:00 AM NY)
        boolean isIntraday = timeframe.equalsIgnoreCase("1d") || timeframe.equalsIgnoreCase("1w");
        if (isIntraday && !prices.isEmpty()) {
            prices = prices.stream()
                    .filter(price -> {
                        // Convert to NY time to check market hours
                        ZonedDateTime nyTime = null;
                        if (price.getZonedTimestamp() != null) {
                            nyTime = price.getZonedTimestamp()
                                    .withZoneSameInstant(ZoneId.of("America/New_York"));
                        } else if (price.getTimestamp() != null) {
                            // Fallback to timestamp if zonedTimestamp is not available
                            nyTime = price.getTimestamp()
                                    .atZone(ZoneId.of("UTC"))
                                    .withZoneSameInstant(ZoneId.of("America/New_York"));
                        }

                        if (nyTime != null) {
                            int hour = nyTime.getHour();
                            int minute = nyTime.getMinute();

                            // Only keep times from 9:30 AM to 4:00 PM NY time
                            return (hour > 9 || (hour == 9 && minute >= 30)) && hour < 16;
                        }

                        return true; // Keep data if we can't determine the time
                    })
                    .collect(Collectors.toList());

            if (!prices.isEmpty()) {
                log.debug("Filtered to {} data points within market hours for {}",
                        prices.size(), symbol);
            }
        }

        // Add fallback logic for 1d timeframe when no data is found
        if (prices.isEmpty() && timeframe.equalsIgnoreCase("1d")) {
            log.info("No data found in 1d window, fetching most recent trading day for {}", symbol);

            // Extend the search to the past 5 days to find the most recent trading day
            LocalDateTime extendedStart = endTime.minusDays(5);
            List<StockPriceDto> extendedPrices = alpacaClient.getHistoricalBars(
                    symbol, alpacaTimeframe, extendedStart, endTime);

            if (!extendedPrices.isEmpty()) {
                // Filter to market hours
                extendedPrices = extendedPrices.stream()
                        .filter(price -> {
                            ZonedDateTime nyTime = price.getZonedTimestamp()
                                    .withZoneSameInstant(ZoneId.of("America/New_York"));
                            int hour = nyTime.getHour();
                            int minute = nyTime.getMinute();

                            // Only keep times from 9:30 AM to 4:00 PM NY time
                            return (hour > 9 || (hour == 9 && minute >= 30)) && hour < 16;
                        })
                        .toList();

                // Group prices by day
                Map<LocalDate, List<StockPriceDto>> pricesByDay = extendedPrices.stream()
                        .collect(Collectors.groupingBy(p -> p.getTimestamp().toLocalDate()));

                // Get the most recent trading day with data
                LocalDate mostRecentDay = pricesByDay.keySet().stream()
                        .max(LocalDate::compareTo)
                        .orElse(null);

                if (mostRecentDay != null) {
                    prices = pricesByDay.get(mostRecentDay);
                    log.info("Using data from most recent trading day: {} for symbol {}",
                            mostRecentDay, symbol);
                }
            }
        }

        // Save to database for future use
        for (StockPriceDto price : prices) {
            saveStockPriceFromDto(price);
        }

        // Add the latest real-time price if available and if it's more recent
        StockPriceDto realtimePrice = realtimePrices.get(symbol);
        if (realtimePrice != null) {
            // Convert to user timezone if needed
            if (userTimezone != null && !userTimezone.equals(realtimePrice.getSourceTimezone())) {
                realtimePrice = convertToUserTimezone(realtimePrice, userTimezone);
            }

            // Check if we should add the real-time price
            if (!prices.isEmpty()) {
                StockPriceDto lastPrice = prices.getLast();

                // Only add if real-time price is more recent
                if (realtimePrice.getTimestamp().isAfter(lastPrice.getTimestamp())) {
                    // Calculate change relative to first price
                    calculateChangeForRealTimePrice(prices, realtimePrice);

                    prices.add(realtimePrice);
                }
            } else {
                // If no historical prices, just add the real-time price
                prices.add(realtimePrice);
            }
        }

        return prices;
    }

    @Override
    public List<SearchResultDto> searchStocks(String keywords) {
        log.info("Searching for stocks matching: {}", keywords);

        // Use the AlpacaClient's search functionality
        return alpacaClient.searchAssets(keywords);
    }

    @Override
    @Transactional
    public void saveStockPrice(StockPrice stockPrice) {
        stockPriceRepository.save(stockPrice);
    }

    /**
     * Converts a StockPriceDto to user's timezone
     */
    private StockPriceDto convertToUserTimezone(StockPriceDto price, ZoneId userTimezone) {
        if (price == null || userTimezone == null ||
                (price.getSourceTimezone() != null && userTimezone.equals(price.getSourceTimezone()))) {
            return price;
        }

        // Create converted timestamp
        ZonedDateTime userTime;
        if (price.getZonedTimestamp() != null) {
            // If we have zoned timestamp, ensure it's properly converted
            // Alpaca API sends UTC timestamps
            ZonedDateTime utcTime = price.getZonedTimestamp();

            log.debug("Original timestamp: {} in timezone {}",
                    utcTime, price.getSourceTimezone() != null ? price.getSourceTimezone() : "UTC");
            // First convert to market timezone to ensure proper market hours
            ZonedDateTime marketTime = utcTime.withZoneSameInstant(ZoneId.of("America/New_York"));

            // Then convert to user timezone to ensure proper market hours
            userTime = marketTime.withZoneSameInstant(userTimezone);

            log.debug("Converted timestamp: {} in timezone {}", userTime, userTimezone);
        } else if (price.getTimestamp() != null) {
            // For legacy non-zoned timestamps, assume they're in UTC
            ZonedDateTime utcTime = price.getTimestamp().atZone(ZoneId.of("UTC"));

            // Convert to market timezone first, then to user timezone
            ZonedDateTime marketTime = utcTime.withZoneSameInstant(ZoneId.of("America/New_York"));
            userTime = marketTime.withZoneSameInstant(userTimezone);

        } else {
            // If no timestamp at all, use current time
            userTime = ZonedDateTime.now(userTimezone);
        }

        return StockPriceDto.builder()
                .symbol(price.getSymbol())
                .price(price.getPrice())
                .open(price.getOpen())
                .high(price.getHigh())
                .low(price.getLow())
                .volume(price.getVolume())
                .change(price.getChange())
                .changePercent(price.getChangePercent())
                .timestamp(price.getTimestamp()) // Keep original timestamp for database
                .zonedTimestamp(userTime) // Use user timezone
                .sourceTimezone(userTimezone) // Mark as converted
                .build();
    }

    /**
     * Converts a stock price entity from database to DTO
     */
    private StockPriceDto convertToDto(StockPrice entity) {
        // Database time is in UTC
        ZonedDateTime utcZoned = entity.getTime().atZone(ZoneId.of("UTC"));

        return StockPriceDto.builder()
                .symbol(entity.getSymbol())
                .price(entity.getPrice())
                .open(entity.getOpen())
                .high(entity.getHigh())
                .low(entity.getLow())
                .volume(entity.getVolume())
                .timestamp(utcZoned.toLocalDateTime())
                .zonedTimestamp(utcZoned)
                .sourceTimezone(ZoneId.of("UTC"))
                .build();
    }

    /**
     * Saves a StockPriceDto to the database
     */
    private void saveStockPriceFromDto(StockPriceDto dto) {
        // Convert to UTC for database storage
        LocalDateTime utcTime;

        if (dto.getZonedTimestamp() != null) {
            // If we have a zoned timestamp, convert to UTC
            utcTime = timezoneService.toUtcLocalDateTime(dto.getZonedTimestamp());
        } else if (dto.getTimestamp() != null && dto.getSourceTimezone() != null) {
            // If we have a local timestamp with source timezone, convert to UTC
            ZonedDateTime zonedTime = dto.getTimestamp().atZone(dto.getSourceTimezone());
            utcTime = timezoneService.toUtcLocalDateTime(zonedTime);
        } else if (dto.getTimestamp() != null) {
            // If we only have a local timestamp without timezone, assume UTC
            utcTime = dto.getTimestamp();
        } else {
            // No timestamp available, use current time
            utcTime = LocalDateTime.now();
        }

        StockPrice entity = new StockPrice();
        entity.setSymbol(dto.getSymbol().toUpperCase());
        entity.setTime(utcTime);
        entity.setPrice(dto.getPrice());
        entity.setOpen(dto.getOpen());
        entity.setHigh(dto.getHigh());
        entity.setLow(dto.getLow());
        entity.setVolume(dto.getVolume());

        saveStockPrice(entity);
    }

    /**
     * Calculates price changes relative to reference price (first in list)
     */
    private void calculateChangesFromReference(List<StockPriceDto> prices) {
        if (prices == null || prices.isEmpty()) {
            return;
        }

        // Sort by timestamp to ensure correct order
        prices.sort(Comparator.comparing(StockPriceDto::getTimestamp));

        // Use first price as reference
        BigDecimal referencePrice = prices.getFirst().getPrice();

        // Calculate changes for each price point
        for (StockPriceDto price : prices) {
            BigDecimal priceChange = price.getPrice().subtract(referencePrice);
            price.setChange(priceChange);

            if (referencePrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal changePercent = priceChange
                        .multiply(new BigDecimal("100"))
                        .divide(referencePrice, 4, RoundingMode.HALF_UP);
                price.setChangePercent(changePercent);
            } else {
                price.setChangePercent(BigDecimal.ZERO);
            }
        }
    }

    private void calculateChangeForRealTimePrice(List<StockPriceDto> prices, StockPriceDto realtimePrice) {
        BigDecimal firstPrice = prices.getFirst().getPrice();
        BigDecimal change = realtimePrice.getPrice().subtract(firstPrice);
        realtimePrice.setChange(change);

        if (firstPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal changePercent = change
                    .multiply(new BigDecimal("100"))
                    .divide(firstPrice, 4, RoundingMode.HALF_UP);
            realtimePrice.setChangePercent(changePercent);
        }
    }

    /**
     * Determines appropriate Alpaca timeframe for a date range
     */
    private String determineTimeframeForDateRange(LocalDateTime start, LocalDateTime end) {
        long days = java.time.Duration.between(start, end).toDays();

        if (days <= 1) {
            return "5Min";
        } else if (days <= 7) {
            return "30Min";
        } else if (days <= 30) {
            return "2Hour";
        } else if (days <= 90) {
            return "12Hour";
        } else if (days <= 365) {
            return "1Day";
        } else {
            return "1Week";
        }
    }

    /**
     * Converts interval string to Alpaca timeframe format
     */
    private String convertToAlpacaTimeframe(String interval) {
        if (interval == null) {
            return "5Min";
        }

        return switch (interval.toLowerCase()) {
            case "1min" -> "1Min";
            case "5min" -> "5Min";
            case "15min" -> "15Min";
            case "30min" -> "30Min";
            case "60min", "1hour" -> "1Hour";
            case "2hour" -> "2Hour";
            case "1day" -> "1Day";
            case "1week" -> "1Week";
            default -> "5Min"; // Default
        };
    }

    /**
     * Scheduled cache cleanup for all stock price caches
     * This is helpful during non-trading hours to free memory
     */
    @Scheduled(cron = "0 0 0 * * *") // Midnight every day
    @CacheEvict(value = {
            "stockPrices_1d", "stockPrices_1w", "stockPrices_1m",
            "stockPrices_3m", "stockPrices_1y", "stockPrices_5y",
            "currentPrices"
    }, allEntries = true)
    public void clearCachesAtMidnight() {
        log.info("Clearing all price caches at midnight");
    }

    /**
     * Clear caches during market open/close transitions
     */
    @Scheduled(cron = "0 30 9,16 * * MON-FRI") // 9:30 AM and 4:00 PM on weekdays
    @CacheEvict(value = {
            "stockPrices_1d", "stockPrices_1w", "currentPrices"
    }, allEntries = true)
    public void checkMarketTransitions() {
        boolean isOpen = isMarketOpen();
        log.info("Market status check: {}", isOpen ? "OPEN" : "CLOSED");
    }

    /**
     * Check if market is currently open
     */
    private boolean isMarketOpen() {
        ZonedDateTime nyTime = ZonedDateTime.now(ZoneId.of("America/New_York"));

        // Weekend check
        int dayOfWeek = nyTime.getDayOfWeek().getValue();
        if (dayOfWeek > 5) {
            return false;
        }

        // Hours check (9:30 AM - 4:00 PM ET)
        int hour = nyTime.getHour();
        int minute = nyTime.getMinute();

        if (hour < 9 || hour > 16) {
            return false;
        }
        return hour != 9 || minute >= 30;
    }

    // Observer Pattern methods
    @Override
    public void registerObserver(StockPriceObserver observer) {
        if (!observers.contains(observer)) {
            observers.add(observer);
        }
    }

    @Override
    public void removeObserver(StockPriceObserver observer) {
        observers.remove(observer);
    }

    @Override
    public void notifyObservers(StockPriceDto stockPrice) {
        for (StockPriceObserver observer : observers) {
            observer.update(stockPrice);
        }
    }
}