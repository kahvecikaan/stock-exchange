package com.stockexchange.stock_platform.service.impl;

import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.model.entity.Holding;
import com.stockexchange.stock_platform.model.entity.WatchlistItem;
import com.stockexchange.stock_platform.repository.HoldingRepository;
import com.stockexchange.stock_platform.repository.WatchlistRepository;
import com.stockexchange.stock_platform.service.StockPriceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DataCollectionService {

    private final StockPriceService stockPriceService;
    private final HoldingRepository holdingRepository;
    private final WatchlistRepository watchlistRepository;

    public DataCollectionService(StockPriceService stockPriceService,
                                 HoldingRepository holdingRepository,
                                 WatchlistRepository watchlistRepository) {
        this.stockPriceService = stockPriceService;
        this.holdingRepository = holdingRepository;
        this.watchlistRepository = watchlistRepository;
    }

    @Scheduled(fixedRate = 600000) // Run every 10 minutes
    public void refreshActiveStocks() {
        // Get all unique symbols from holdings and watchlists
        Set<String> symbols = new HashSet<>();

        // Add symbols from all user holdings
        List<Holding> allHoldings = holdingRepository.findAll();
        symbols.addAll(allHoldings.stream()
                .map(Holding::getSymbol)
                .collect(Collectors.toSet()));

        // Add symbols from all user watchlists
        List<WatchlistItem> allWatchlistItems = watchlistRepository.findAll();
        symbols.addAll(allWatchlistItems.stream()
                .map(WatchlistItem::getSymbol)
                .collect(Collectors.toSet()));

        log.info("Refreshing data for {} active stocks", symbols.size());

        // Refresh prices with a delay between requests to avoid hitting API limits
        int delay = 0;
        for (String symbol : symbols) {
            // Schedule with increasing delay to space out requests
            try {
                Thread.sleep(delay);
                StockPriceDto price = stockPriceService.getCurrentPrice(symbol);
                log.debug("Updated price for {}: {}", symbol, price.getPrice());
                delay = 12000; // 12 seconds delay to stay under 5 requests/minute limit
            } catch (Exception e) {
                log.error("Error updating price for {}: {}", symbol, e.getMessage());
            }
        }

        log.info("Completed refreshing data for all active stocks");
    }
}