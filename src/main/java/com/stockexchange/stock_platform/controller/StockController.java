package com.stockexchange.stock_platform.controller;


import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.service.StockPriceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
@Slf4j
public class StockController {

    private final StockPriceService stockPriceService;

    @GetMapping("/{symbol}/price")
    public ResponseEntity<StockPriceDto> getCurrentPrice(@PathVariable String symbol) {
        log.info("Fetching current price for: {}", symbol);
        StockPriceDto price = stockPriceService.getCurrentPrice(symbol);
        return ResponseEntity.ok(price);
    }

    @GetMapping("/{symbol}/historical")
    public ResponseEntity<List<StockPriceDto>> getHistoricalPrices(
            @PathVariable String symbol,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        log.info("Fetching historical data prices for {} between {} and {}", symbol, startDate, endDate);
        LocalDateTime start = LocalDateTime.parse(startDate);
        LocalDateTime end = LocalDateTime.parse(endDate);
        List<StockPriceDto> prices = stockPriceService.getHistoricalPrices(symbol, start, end);
        return ResponseEntity.ok(prices);
    }

    @GetMapping("/{symbol}/intraday")
    public ResponseEntity<List<StockPriceDto>> getIntradayPrices(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "5min") String interval) {
        log.info("Fetching intraday prices for {} with interval: {}", symbol, interval);
        List<StockPriceDto> prices = stockPriceService.getIntradayPrices(symbol, interval);
        return ResponseEntity.ok(prices);
    }

}
