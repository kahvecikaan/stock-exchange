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
import java.util.stream.Collectors;

@Service
@Slf4j
public class StockPriceServiceImpl implements StockPriceService, StockPriceSubject {

    private final AlphaVantageClient apiClient;
    private final StockPriceRepository stockPriceRepository;
    private final List<StockPriceObserver> observers = new ArrayList<>();

    public StockPriceServiceImpl(AlphaVantageClient apiClient, StockPriceRepository stockPriceRepository) {
        this.apiClient = apiClient;
        this.stockPriceRepository = stockPriceRepository;
    }

    @Override
    public StockPriceDto getCurrentPrice(String symbol) {
        StockPriceDto stockPrice = apiClient.getCurrentStockPrice(symbol);
        saveStockPriceFromDto(stockPrice);
        notifyObservers(stockPrice);
        return stockPrice;
    }

    @Override
    public List<StockPriceDto> getHistoricalPrices(String symbol, LocalDateTime startTime, LocalDateTime endTime) {
        // First check if we have the data in our database
        List<StockPrice> prices = stockPriceRepository.findBySymbolAndTimeBetweenOrderByTimeAsc(
                symbol, startTime, endTime);

        // If we don't have enough data, fetch it from the API
        if (prices.isEmpty() || prices.size() < 5) { // Arbitrary threshold - if we have less than 5 points
            int daysBetween = (int) java.time.Duration.between(startTime, endTime).toDays() + 1;
            List<StockPriceDto> historicalPrices = apiClient.getHistoricalDailyPrices(symbol, daysBetween);

            // Save the fetched data to database
            for (StockPriceDto priceDto : historicalPrices) {
                if (priceDto.getTimestamp().isAfter(startTime) && priceDto.getTimestamp().isBefore(endTime)) {
                    saveStockPriceFromDto(priceDto);
                }
            }

            return historicalPrices.stream()
                    .filter(p -> p.getTimestamp().isAfter(startTime) && p.getTimestamp().isBefore(endTime))
                    .collect(Collectors.toList());
        }

        // If we have data in the database, use that
        return prices.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<StockPriceDto> getIntradayPrices(String symbol, String interval) {
        // Fetch intraday data from API
        List<StockPriceDto> intradayPrices = apiClient.getIntradayPrices(symbol, interval);

        // The list is already sorted newest first (from AlphaVantageClient)
        // Calculate change and change percent between consecutive time points
        for (int i = 0; i < intradayPrices.size() - 1; i++) {
            StockPriceDto currentPrice = intradayPrices.get(i);
            StockPriceDto previousPrice = intradayPrices.get(i + 1);

            // Calculate absolute change (current - previous)
            BigDecimal priceChange = currentPrice.getPrice().subtract(previousPrice.getPrice());
            currentPrice.setChange(priceChange);

            // Calculate percentage change ((current - previous) / previous) * 100
            if (previousPrice.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal changePercent = priceChange
                        .multiply(new BigDecimal("100"))
                        .divide(previousPrice.getPrice(), 4, RoundingMode.HALF_UP);
                currentPrice.setChangePercent(changePercent);
            } else {
                // Avoid division by zero
                currentPrice.setChangePercent(BigDecimal.ZERO);
            }
        }

        // Handle the oldest data point (it has no previous point to compare with)
        if (!intradayPrices.isEmpty() && intradayPrices.size() > 1) {
            StockPriceDto oldestPrice = intradayPrices.getLast();

            // For the oldest point, calculate change from the second-oldest point
            // This gives us a reasonable "direction" value for the start of our data
            StockPriceDto secondOldestPrice = intradayPrices.get(intradayPrices.size() - 2);
            BigDecimal priceChange = oldestPrice.getPrice().subtract(secondOldestPrice.getPrice());

            // The direction is inverted because we're going backward in time
            oldestPrice.setChange(priceChange.negate());

            if (secondOldestPrice.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal changePercent = priceChange
                        .multiply(new BigDecimal("100"))
                        .divide(secondOldestPrice.getPrice(), 4, RoundingMode.HALF_UP)
                        .negate();
                oldestPrice.setChangePercent(changePercent);
            } else {
                oldestPrice.setChangePercent(BigDecimal.ZERO);
            }
        } else if (intradayPrices.size() == 1) {
            // If we only have one data point, set change to zero
            intradayPrices.get(0).setChange(BigDecimal.ZERO);
            intradayPrices.get(0).setChangePercent(BigDecimal.ZERO);
        }

        return intradayPrices;
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

    @Override
    @Scheduled(fixedRate = 300000) // Run every 5 minutes
    public void refreshPrices(List<String> symbols) {
        log.info("Refreshing prices for {} symbols", symbols.size());
        for (String symbol : symbols) {
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