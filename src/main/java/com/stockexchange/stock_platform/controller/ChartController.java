package com.stockexchange.stock_platform.controller;

import com.stockexchange.stock_platform.dto.ChartDataDto;
import com.stockexchange.stock_platform.service.ChartDataService;
import com.stockexchange.stock_platform.service.impl.TimezoneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;

@RestController
@RequestMapping("/api/charts")
@RequiredArgsConstructor
@Slf4j
public class ChartController {

    private final ChartDataService chartDataService;
    private final TimezoneService timezoneService;

    @GetMapping("/stock/{symbol}")
    public ResponseEntity<ChartDataDto> getStockPriceChart(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(required = false) String timezone) {
        ZoneId targetTimezone = timezoneService.parseTimezone(timezone);

        log.info("Generating {} price chart for {} (timezone: {})", timeframe, symbol, timezone);
        ChartDataDto chartData = chartDataService.getStockPriceChart(symbol, timeframe, targetTimezone);

        return ResponseEntity.ok(chartData);
    }

    @GetMapping("/portfolio/{userId}")
    public ResponseEntity<ChartDataDto> getPortfolioValueChart(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(required = false) String timezone) {

        ZoneId targetTimezone = timezoneService.parseTimezone(timezone);
        log.info("Generating {} portfolio chart for user ID: {} (timezone: {})",
                timeframe, userId, targetTimezone.getId());

        ChartDataDto chartData = chartDataService.getPortfolioValueChart(
                userId, timeframe, targetTimezone);

        return ResponseEntity.ok(chartData);
    }

    @GetMapping("/comparison")
    public ResponseEntity<ChartDataDto> getComparisonChart(
            @RequestParam String[] symbols,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(required = false) String timezone) {

        ZoneId targetTimezone = timezoneService.parseTimezone(timezone);
        log.info("Generating {} comparison chart for {} symbols (timezone: {})",
                timeframe, symbols.length, targetTimezone.getId());

        ChartDataDto chartData = chartDataService.getComparisonChart(
                symbols, timeframe, targetTimezone);

        return ResponseEntity.ok(chartData);
    }
}
