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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class StockPriceServiceImpl implements StockPriceService, StockPriceSubject {

    private final AlphaVantageClient apiClient;
    private final StockPriceRepository stockPriceRepository;
    private final List<StockPriceObserver> observers = new ArrayList<>();
    // Symbol tracking system
    private final Set<String> activeSymbols = ConcurrentHashMap.newKeySet();

    public StockPriceServiceImpl(AlphaVantageClient apiClient, StockPriceRepository stockPriceRepository) {
        this.apiClient = apiClient;
        this.stockPriceRepository = stockPriceRepository;
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
        // Register this symbol for automatic tracking
        registerSymbolForTracking(symbol);

        StockPriceDto stockPrice = apiClient.getCurrentStockPrice(symbol);
        saveStockPriceFromDto(stockPrice);
        notifyObservers(stockPrice);
        return stockPrice;
    }

    @Override
    public List<StockPriceDto> getHistoricalPrices(String symbol, LocalDateTime startTime, LocalDateTime endTime) {
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

        // Apply percentage calculations to either API or database data
        calculateChangesFromReference(historicalPrices);

        return historicalPrices;
    }

    @Override
    public List<StockPriceDto> getIntradayPrices(String symbol, String interval) {
        // Register this symbol for automatic tracking
        registerSymbolForTracking(symbol);

        // Fetch intraday data from API
        // The list already sorted newest first (from Alpha Vantage)
        List<StockPriceDto> intradayPrices = apiClient.getIntradayPrices(symbol, interval);

        if(intradayPrices.isEmpty()) {
            return intradayPrices;
        }

        calculateChangesFromReference(intradayPrices);

        return intradayPrices;
    }

    @Override
    public List<StockPriceDto> getWeeklyPrices(String symbol, int weeks) {
        // Register this symbol for automatic tracking
        registerSymbolForTracking(symbol);

        List<StockPriceDto> weeklyPrices = apiClient.getWeeklyPrices(symbol);

        // Limit to requested number of weeks
        return weeklyPrices.stream()
                .limit(weeks)
                .collect(Collectors.toList());
    }

    @Override
    public List<StockPriceDto> getMonthlyPrices(String symbol, int months) {
        // Register this symbol for automatic tracking
        registerSymbolForTracking(symbol);

        List<StockPriceDto> monthlyPrices = apiClient.getMonthlyPrices(symbol);

        // Limit to requested number of months
        return monthlyPrices.stream()
                .limit(months)
                .collect(Collectors.toList());
    }

    @Override
    public List<StockPriceDto> getPricesForTimeframe(String symbol, String timeframe) {
        // Register this symbol for automatic tracking
        registerSymbolForTracking(symbol);

        List<StockPriceDto> prices = switch (timeframe.toLowerCase()) {
            case "1d" ->
                // 1 day - use intraday data with 5 min intervals
                    getIntradayPrices(symbol, "5min");
            case "1w" ->
                // 1 week - use intraday data with 30 min
                    getIntradayPrices(symbol, "30min");
            case "1m" ->
                // 1 month - use daily data
                    getHistoricalPrices(symbol, LocalDateTime.now().minusMonths(1), LocalDateTime.now());
            case "3m" ->
                // 3 months - use daily data
                    getHistoricalPrices(symbol, LocalDateTime.now().minusMonths(3), LocalDateTime.now());
            case "1y" ->
                // 1 year - use weekly data
                    getWeeklyPrices(symbol, 52);
            case "5y" ->
                // 5 years - use monthly data
                    getMonthlyPrices(symbol, 60);
            default ->
                // Default to 1 month
                    getHistoricalPrices(symbol, LocalDateTime.now().minusMonths(1), LocalDateTime.now());
        };

        // Apply percentage change calculation
        calculateChangesFromReference(prices);

        return prices;
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
     * @param prices List of price data points, sorted with the oldest last
     */
    private void calculateChangesFromReference(List<StockPriceDto> prices) {
        if(prices.isEmpty()) return;

        // Alpha Vantage returns data in descending order (newest first),
        // so the last element is our oldest price to use as reference
        BigDecimal referencePrice = prices.getLast().getPrice();

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
        StockPrice entity = new StockPrice();
        entity.setSymbol(dto.getSymbol());
        entity.setTime(dto.getTimestamp());
        entity.setPrice(dto.getPrice());
        entity.setOpen(dto.getOpen());
        entity.setHigh(dto.getHigh());
        entity.setLow(dto.getLow());
        entity.setVolume(dto.getVolume());

        saveStockPrice(entity);
    }

    private StockPriceDto convertToDto(StockPrice entity) {
        return StockPriceDto.builder()
                .symbol(entity.getSymbol())
                .price(entity.getPrice())
                .open(entity.getOpen())
                .high(entity.getHigh())
                .low(entity.getLow())
                .volume(entity.getVolume())
                .timestamp(entity.getTime())
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