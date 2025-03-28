package com.stockexchange.stock_platform.service.impl;

import com.stockexchange.stock_platform.dto.SearchResultDto;
import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.model.entity.StockPrice;
import com.stockexchange.stock_platform.pattern.observer.StockPriceObserver;
import com.stockexchange.stock_platform.pattern.observer.StockPriceSubject;
import com.stockexchange.stock_platform.repository.StockPriceRepository;
import com.stockexchange.stock_platform.service.StockPriceService;
import com.stockexchange.stock_platform.service.api.AlphaVantageClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class StockPriceServiceImpl implements StockPriceService, StockPriceSubject {

    private final AlphaVantageClient apiClient;
    private final StockPriceRepository stockPriceRepository;
    private final TimezoneService timezoneService;
    private final List<StockPriceObserver> observers = new ArrayList<>();
    private final Set<String> activeSymbols = ConcurrentHashMap.newKeySet();

    public StockPriceServiceImpl(AlphaVantageClient apiClient,
                                 StockPriceRepository stockPriceRepository,
                                 TimezoneService timezoneService) {
        this.apiClient = apiClient;
        this.stockPriceRepository = stockPriceRepository;
        this.timezoneService = timezoneService;
    }

    /**
     * Register a symbol for automatic tracking in the background refresh
     * @param symbol The stock symbol to track
     */
    public void registerSymbolForTracking(String symbol) {
        if (symbol != null && !symbol.isBlank()) {
            activeSymbols.add(symbol.toUpperCase());
            log.debug("Registered symbol for tracking: {}", symbol);
        }
    }

    @Override
    public StockPriceDto getCurrentPrice(String symbol) {
        // Call the timezone-aware version with default market timezone
        return getCurrentPrice(symbol, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    @Override
    public StockPriceDto getCurrentPrice(String symbol, ZoneId timezone) {
        // Register this symbol for automatic tracking
        registerSymbolForTracking(symbol);

        StockPriceDto stockPrice = apiClient.getCurrentStockPrice(symbol);

        // Ensure timezone information is properly set
        if (stockPrice.getZonedTimestamp() == null && stockPrice.getTimestamp() != null) {
            // If the API client didn't set zonedTimestamp, create it using market timezone
            ZonedDateTime marketTime = stockPrice.getTimestamp()
                    .atZone(TimezoneService.DEFAULT_MARKET_TIMEZONE);
            stockPrice.setZonedTimestamp(marketTime);
            stockPrice.setSourceTimezone(TimezoneService.DEFAULT_MARKET_TIMEZONE);
        }

        // Convert to requested timezone if different from source
        if (timezone != null && stockPrice.getSourceTimezone() != null &&
                !timezone.equals(stockPrice.getSourceTimezone())) {
            assert stockPrice.getZonedTimestamp() != null;
            ZonedDateTime convertedTime = stockPrice.getZonedTimestamp()
                    .withZoneSameInstant(timezone);
            stockPrice.setZonedTimestamp(convertedTime);
        }

        saveStockPriceFromDto(stockPrice);
        notifyObservers(stockPrice);
        return stockPrice;
    }

    @Override
    public List<StockPriceDto> getHistoricalPrices(String symbol, LocalDateTime startTime, LocalDateTime endTime) {
        return getHistoricalPrices(symbol, startTime, endTime, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    @Override
    public List<StockPriceDto> getHistoricalPrices(String symbol, LocalDateTime startTime, LocalDateTime endTime, ZoneId timezone) {
        // Register this symbol for automatic tracking
        registerSymbolForTracking(symbol);

        List<StockPriceDto> historicalPrices;

        // First check if we have the data in our database
        List<StockPrice> prices = stockPriceRepository.findBySymbolAndTimeBetweenOrderByTimeAsc(
                symbol, startTime, endTime);

        // If we don't have enough data, fetch it from the API
        if (prices.isEmpty() || prices.size() < 5) { // Arbitrary threshold - if we have less than 5 points
            int daysBetween = (int) java.time.Duration.between(startTime, endTime).toDays() + 1;
            historicalPrices = apiClient.getHistoricalDailyPrices(symbol, daysBetween);

            // Save the fetched data to database
            for (StockPriceDto priceDto : historicalPrices) {
                if (priceDto.getTimestamp().isAfter(startTime) && priceDto.getTimestamp().isBefore(endTime)) {
                    saveStockPriceFromDto(priceDto);
                }
            }

            historicalPrices =  historicalPrices.stream()
                    .filter(p -> p.getTimestamp().isAfter(startTime) && p.getTimestamp().isBefore(endTime))
                    .collect(Collectors.toList());
        } else {
            // If we have the data in the database, convert to DTOs
            historicalPrices = prices.stream()
                    .map(this::convertToDto)
                    .collect(Collectors.toList());
        }

        // Convert to requested timezone if needed
        if (timezone != null) {
            for (StockPriceDto price : historicalPrices) {
                // Ensure timezone information is set
                if (price.getZonedTimestamp() == null && price.getTimestamp() != null) {
                    // If zonedTimestamp isn't set, create it using UTC (database default)
                    ZonedDateTime utcTime = price.getTimestamp().atZone(ZoneId.of("UTC"));
                    price.setZonedTimestamp(utcTime);
                    price.setSourceTimezone(ZoneId.of("UTC"));
                }

                // Convert to target timezone
                if (price.getZonedTimestamp() != null && price.getSourceTimezone() != null &&
                        !timezone.equals(price.getSourceTimezone())) {
                    price.setZonedTimestamp(
                            timezoneService.convertTimezone(price.getZonedTimestamp(), timezone)
                    );
                }
            }
        }

        return historicalPrices;
    }

    @Override
    public List<StockPriceDto> getIntradayPrices(String symbol, String interval) {
        // Call the timezone-aware version with the default market timezone
        return getIntradayPrices(symbol, interval, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    @Override
    public List<StockPriceDto> getIntradayPrices(String symbol, String interval, ZoneId timezone) {
        // Register this symbol for automatic tracking
        registerSymbolForTracking(symbol);

        // Fetch intraday data from API
        // The list already sorted newest first (from Alpha Vantage)
        List<StockPriceDto> intradayPrices = apiClient.getIntradayPrices(symbol, interval);

        if(intradayPrices.isEmpty()) {
            return intradayPrices;
        }

        // Ensure timezone information is properly set
        for (StockPriceDto price : intradayPrices) {
            if (price.getZonedTimestamp() == null && price.getTimestamp() != null) {
                ZonedDateTime marketTime = price.getTimestamp()
                        .atZone(TimezoneService.DEFAULT_MARKET_TIMEZONE);
                price.setZonedTimestamp(marketTime);
                price.setSourceTimezone(TimezoneService.DEFAULT_MARKET_TIMEZONE);
            }

            // Convert to requested timezone if needed
            if (timezone != null && price.getSourceTimezone() != null &&
                    !timezone.equals(price.getSourceTimezone())) {
                price.setZonedTimestamp(
                        timezoneService.convertTimezone(price.getZonedTimestamp(), timezone)
                );
            }
        }

        return intradayPrices;
    }

    @Override
    public List<StockPriceDto> getWeeklyPrices(String symbol, int weeks) {
        // Call the timezone-aware version with default market timezone
        return getWeeklyPrices(symbol, weeks, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    @Override
    public List<StockPriceDto> getWeeklyPrices(String symbol, int weeks, ZoneId timezone) {
        // Register this symbol for automatic tracking
        registerSymbolForTracking(symbol);

        List<StockPriceDto> weeklyPrices = apiClient.getWeeklyPrices(symbol);

        // Ensure timezone information is properly set
        for (StockPriceDto price : weeklyPrices) {
            if (price.getZonedTimestamp() == null && price.getTimestamp() != null) {
                ZonedDateTime marketTime = price.getTimestamp()
                        .atZone(TimezoneService.DEFAULT_MARKET_TIMEZONE);
                price.setZonedTimestamp(marketTime);
                price.setSourceTimezone(TimezoneService.DEFAULT_MARKET_TIMEZONE);
            }

            // Convert to requested timezone if needed
            if (timezone != null && price.getSourceTimezone() != null &&
                    !timezone.equals(price.getSourceTimezone())) {
                price.setZonedTimestamp(
                        timezoneService.convertTimezone(price.getZonedTimestamp(), timezone)
                );
            }
        }

        // Limit to requested number of weeks
        return weeklyPrices.stream()
                .limit(weeks)
                .collect(Collectors.toList());
    }

    @Override
    public List<StockPriceDto> getMonthlyPrices(String symbol, int months) {
        // Call the timezone-aware version with default market timezone
        return getMonthlyPrices(symbol, months, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    @Override
    public List<StockPriceDto> getMonthlyPrices(String symbol, int months, ZoneId timezone) {
        // Register this symbol for automatic tracking
        registerSymbolForTracking(symbol);

        List<StockPriceDto> monthlyPrices = apiClient.getMonthlyPrices(symbol);

        // Ensure timezone information is properly set
        for (StockPriceDto price : monthlyPrices) {
            if (price.getZonedTimestamp() == null && price.getTimestamp() != null) {
                ZonedDateTime marketTime = price.getTimestamp()
                        .atZone(TimezoneService.DEFAULT_MARKET_TIMEZONE);
                price.setZonedTimestamp(marketTime);
                price.setSourceTimezone(TimezoneService.DEFAULT_MARKET_TIMEZONE);
            }

            // Convert to requested timezone if needed
            if (timezone != null && price.getSourceTimezone() != null &&
                    !timezone.equals(price.getSourceTimezone())) {
                price.setZonedTimestamp(
                        timezoneService.convertTimezone(price.getZonedTimestamp(), timezone)
                );
            }
        }

        // Limit to requested number of months
        return monthlyPrices.stream()
                .limit(months)
                .collect(Collectors.toList());
    }


    @Override
    public List<StockPriceDto> getPricesForTimeframe(String symbol, String timeframe) {
        // Call the timezone-aware version with default market timezone
        return getPricesForTimeframe(symbol, timeframe, TimezoneService.DEFAULT_MARKET_TIMEZONE);
    }

    @Override
    public List<StockPriceDto> getPricesForTimeframe(String symbol, String timeframe, ZoneId timezone) {
        // Register this symbol for automatic tracking
        registerSymbolForTracking(symbol);

        LocalDateTime now = LocalDateTime.now();
        List<StockPriceDto> prices;

        switch (timeframe.toLowerCase()) {
            case "1d":
                List<StockPriceDto> dailyPrices = getIntradayPrices(symbol, "5min", timezone);
                prices = filterForMostRecentTradingDay(dailyPrices);

                break;
            case "1w":
                // 1 week - use intraday data with 30 min intervals
                List<StockPriceDto> allWeeklyPrices = getIntradayPrices(symbol, "30min", timezone);

                // Get current date in NYSE timezone
                LocalDate todayNY = LocalDate.now(ZoneId.of("America/New_York"));
                // Calculate the date 7 days ago
                LocalDate weekAgoNY = todayNY.minusDays(7);

                // Filter for the last 7 days of trading data
                prices = allWeeklyPrices.stream()
                        .filter(price -> {
                            // Convert timestamp to NYSE timezone for date comparison
                            ZonedDateTime nyTime = price.getZonedTimestamp()
                                    .withZoneSameInstant(ZoneId.of("America/New_York"));
                            LocalDate priceDate = nyTime.toLocalDate();

                            // Keep data points from the last 7 days
                            return !priceDate.isBefore(weekAgoNY) && !priceDate.isAfter(todayNY);
                        })
                        .collect(Collectors.toList());

                // If we got no data from the last 7 days, use the most recent available data
                if (prices.isEmpty()) {
                    // Take the most recent 7 days of data available
                    prices = getRecentTradingData(allWeeklyPrices, 7);
                }

                break;
            case "1m":
                // 1 month - use daily data
                prices = getHistoricalPrices(symbol, now.minusMonths(1), now, timezone);
                break;
            case "3m":
                // 3 months - use daily data
                prices = getHistoricalPrices(symbol, now.minusMonths(3), now, timezone);
                break;
            case "1y":
                // 1 year - use weekly data
                prices = getWeeklyPrices(symbol, 52, timezone);
                break;
            case "5y":
                // 5 years - use monthly data
                prices = getMonthlyPrices(symbol, 60, timezone);
                break;
            default:
                // Default to 1 month
                prices = getHistoricalPrices(symbol, now.minusMonths(1), now, timezone);
                break;
        }

        calculateChangesFromReference(prices);
        return prices;
    }


    /**
     * Filters data to show only the most recent trading day
     */
    public List<StockPriceDto> filterForMostRecentTradingDay(List<StockPriceDto> allPrices) {
        if(allPrices.isEmpty()) return allPrices;

        // Get current date in NYSE timezone
        LocalDate todayNY = LocalDate.now(ZoneId.of("America/New_York"));


        // First, try to filter today's data
        List<StockPriceDto> todayPrices = allPrices.stream()
                .filter(price -> {
                    ZonedDateTime nyTime = price.getZonedTimestamp()
                            .withZoneSameInstant(ZoneId.of("America/New_York"));
                    return nyTime.toLocalDate().equals(todayNY);
                })
                .collect(Collectors.toList());

        // If we have today's data, use it
        if(!todayPrices.isEmpty()) {
            return todayPrices;
        } else {
            Optional<LocalDate> mostRecentTradingDay = allPrices.stream()
                    .map(price -> price.getZonedTimestamp()
                            .withZoneSameInstant(ZoneId.of("America/New_York"))
                            .toLocalDate())
                    .max(LocalDate::compareTo);

            if(mostRecentTradingDay.isPresent()) {
                LocalDate recentDay = mostRecentTradingDay.get();
                return allPrices.stream()
                        .filter(price -> {
                            ZonedDateTime nyTime = price.getZonedTimestamp()
                                    .withZoneSameInstant(ZoneId.of("America/New_York"));
                            return nyTime.toLocalDate().equals(recentDay);
                        }).collect(Collectors.toList());
            } else {
                // Fallback if no data available
                return allPrices;
            }
        }
    }

    /**
     * Gets the most recent N days of trading data
     */
    private List<StockPriceDto> getRecentTradingData(List<StockPriceDto> allPrices, int days) {
        if(allPrices.isEmpty()) return allPrices;

        List<LocalDate> tradingDays = allPrices.stream()
                .map(price -> price.getZonedTimestamp()
                        .withZoneSameInstant(ZoneId.of("America/New_York"))
                        .toLocalDate())
                .distinct()
                .sorted()
                .toList();

        List<LocalDate> recentDays = tradingDays.stream()
                .limit(days)
                .toList();

        return allPrices.stream()
                .filter(prices -> {
                    LocalDate priceDate = prices.getZonedTimestamp()
                            .withZoneSameInstant(ZoneId.of("America/New_York"))
                            .toLocalDate();

                    return recentDays.contains(priceDate);
                }).collect(Collectors.toList());
    }

    @Override
    public List<SearchResultDto> searchStocks(String keywords) {
        log.info("Searching for stocks matching: {}", keywords);

        List<Map<String, String>> searchResults = apiClient.searchSymbol(keywords);

        return searchResults.stream()
                .map(result -> SearchResultDto.builder()
                        .symbol(result.get("1. symbol"))
                        .name(result.get("2. name"))
                        .type(result.get("3. type"))
                        .region(result.get("4. region"))
                        .currency(result.get("8. currency"))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Scheduled method that runs automatically to refresh prices
     * for all actively tracked symbols
     */
    @Scheduled(fixedRate = 300000) // Run every 5 minutes
    public void refreshActiveSymbols() {
        if (activeSymbols.isEmpty()) {
            log.info("No active symbols to refresh");
            return;
        }

        log.info("Refreshing prices for {} active symbols", activeSymbols.size());
        for (String symbol : activeSymbols) {
            try {
                StockPriceDto price = apiClient.getCurrentStockPrice(symbol);
                saveStockPriceFromDto(price);
                notifyObservers(price);
                log.debug("Refreshed price for {}: {}", symbol, price.getPrice());
            } catch (Exception e) {
                log.error("Error refreshing price for {}: {}", symbol, e.getMessage());
            }
        }
    }

    @Override
    @Transactional
    public void saveStockPrice(StockPrice stockPrice) {
        stockPriceRepository.save(stockPrice);
    }

    /**
     * Calculates price changes relative to the reference (oldest) price
     * @param prices List of price data points
     */
    private void calculateChangesFromReference(List<StockPriceDto> prices) {
        if(prices.isEmpty()) return;

        // Sort oldest to newest to get proper reference point
        prices.sort(Comparator.comparing(StockPriceDto::getTimestamp));
        BigDecimal referencePrice = prices.getFirst().getPrice();

        for (StockPriceDto pricePoint : prices) {
            BigDecimal priceChange = pricePoint.getPrice().subtract(referencePrice);
            pricePoint.setChange(priceChange);

            if (referencePrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal changePercent = priceChange
                        .multiply(new BigDecimal("100"))
                        .divide(referencePrice, 4, RoundingMode.HALF_UP);
                pricePoint.setChangePercent(changePercent);
            } else {
                pricePoint.setChangePercent(BigDecimal.ZERO);
            }
        }
    }

    private void saveStockPriceFromDto(StockPriceDto dto) {
        // Convert timestamp to UTC for database storage
        LocalDateTime utcTime;

        if (dto.getZonedTimestamp() != null) {
            // If we have a zoned timestamp, convert to UTC
            utcTime = timezoneService.toUtcLocalDateTime(dto.getZonedTimestamp());
        } else if (dto.getTimestamp() != null && dto.getSourceTimezone() != null) {
            // If we have a local timestamp with source timezone, convert to UTC
            ZonedDateTime zonedTime = dto.getTimestamp().atZone(dto.getSourceTimezone());
            utcTime = timezoneService.toUtcLocalDateTime(zonedTime);
        } else if (dto.getTimestamp() != null) {
            // If we only have a local timestamp without timezone, assume it's in market timezone
            ZonedDateTime zonedTime = dto.getTimestamp().atZone(TimezoneService.DEFAULT_MARKET_TIMEZONE);
            utcTime = timezoneService.toUtcLocalDateTime(zonedTime);
        } else {
            // No timestamp available, use current time
            utcTime = LocalDateTime.now();
        }

        StockPrice entity = new StockPrice();
        entity.setSymbol(dto.getSymbol());
        entity.setTime(utcTime);  // Store in UTC
        entity.setPrice(dto.getPrice());
        entity.setOpen(dto.getOpen());
        entity.setHigh(dto.getHigh());
        entity.setLow(dto.getLow());
        entity.setVolume(dto.getVolume());

        saveStockPrice(entity);
    }

    private StockPriceDto convertToDto(StockPrice entity) {
        // The time from database is in UTC, convert to ZonedDateTime
        ZonedDateTime utcZoned = entity.getTime().atZone(ZoneId.of("UTC"));

        // Convert to market timezone for business logic
        ZonedDateTime marketZoned = utcZoned.withZoneSameInstant(timezoneService.DEFAULT_MARKET_TIMEZONE);

        return StockPriceDto.builder()
                .symbol(entity.getSymbol())
                .price(entity.getPrice())
                .open(entity.getOpen())
                .high(entity.getHigh())
                .low(entity.getLow())
                .volume(entity.getVolume())
                .timestamp(marketZoned.toLocalDateTime())  // Keep for backward compatibility
                .zonedTimestamp(marketZoned)               // Store the zoned timestamp
                .sourceTimezone(TimezoneService.DEFAULT_MARKET_TIMEZONE)  // Note the timezone
                .build();
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