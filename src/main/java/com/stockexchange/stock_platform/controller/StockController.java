package com.stockexchange.stock_platform.controller;


import com.stockexchange.stock_platform.dto.SearchResultDto;
import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.service.StockPriceService;
import com.stockexchange.stock_platform.service.impl.TimezoneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
@Slf4j
public class StockController {

    private final StockPriceService stockPriceService;
    private final TimezoneService timezoneService;

    @GetMapping("/{symbol}/price")
    public ResponseEntity<StockPriceDto> getCurrentPrice(
            @PathVariable String symbol,
            @RequestParam(required = false) String timezone) {

        log.info("Fetching current price for: {} (timezone: {})", symbol, timezone);

        ZoneId targetTimezone = timezoneService.parseTimezone(timezone);
        StockPriceDto price = stockPriceService.getCurrentPrice(symbol, targetTimezone);

        return ResponseEntity.ok(price);
    }

    @GetMapping("/{symbol}/historical")
    public ResponseEntity<List<StockPriceDto>> getHistoricalPrices(
            @PathVariable String symbol,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) String timezone) {

        log.info("Fetching historical data prices for {} between {} and {} (timezone: {})",
                symbol, startDate, endDate, timezone);

        LocalDateTime start = LocalDateTime.parse(startDate);
        LocalDateTime end = LocalDateTime.parse(endDate);
        ZoneId targetTimezone = timezoneService.parseTimezone(timezone);

        List<StockPriceDto> prices = stockPriceService.getHistoricalPrices(
                symbol, start, end, targetTimezone);

        return ResponseEntity.ok(prices);
    }

    @GetMapping("/{symbol}/intraday")
    public ResponseEntity<List<StockPriceDto>> getIntradayPrices(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "5min") String interval,
            @RequestParam(required = false) String timezone) {

        log.info("Fetching intraday prices for {} with interval: {} (timezone: {})",
                symbol, interval, timezone);

        ZoneId targetTimezone = timezoneService.parseTimezone(timezone);
        List<StockPriceDto> prices = stockPriceService.getIntradayPrices(
                symbol, interval, targetTimezone);

        return ResponseEntity.ok(prices);
    }

    @GetMapping("/search")
    public ResponseEntity<List<SearchResultDto>> searchStocks(@RequestParam String keywords) {
        log.info("Searching for stocks matching: {}", keywords);
        List<SearchResultDto> results = stockPriceService.searchStocks(keywords);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/timezones")
    public ResponseEntity<Map<String, Object>> getTimezones() {
        // Return common timezones and the default market timezone
        return ResponseEntity.ok(Map.of(
                "commonTimezones", timezoneService.getCommonTimezones(),
                "defaultMarketTimezone", TimezoneService.DEFAULT_MARKET_TIMEZONE,
                "currentServerTime", LocalDateTime.now().toString()
        ));
    }
}
