package com.stockexchange.stock_platform.controller;

import com.stockexchange.stock_platform.dto.ChartDataDto;
import com.stockexchange.stock_platform.service.ChartDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/charts")
@RequiredArgsConstructor
@Slf4j
public class ChartController {

    private final ChartDataService chartDataService;

    @GetMapping("/stock/{symbol}")
    public ResponseEntity<ChartDataDto> getStockPriceChart(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1m") String timeframe) {
        log.info("Generating {} price chart for {}", timeframe, symbol);
        ChartDataDto chartData = chartDataService.getStockPriceChart(symbol, timeframe);

        return ResponseEntity.ok(chartData);
    }

    @GetMapping("/portfolio/{userId}")
    public ResponseEntity<ChartDataDto> getPortfolioValueChart(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1m") String timeframe) {
        log.info("Generating {} portfolio chart for user ID: {}", timeframe, userId);
        ChartDataDto chartData = chartDataService.getPortfolioValueChart(userId, timeframe);

        return ResponseEntity.ok(chartData);
    }

    @GetMapping("/comparison")
    public ResponseEntity<ChartDataDto> getComparisonChart(
            @RequestParam String[] symbols,
            @RequestParam(defaultValue = "1m") String timeframe) {
        log.info("Generating {} comparison chart for {} symbols", timeframe, symbols);
        ChartDataDto chartData = chartDataService.getComparisonChart(symbols, timeframe);

        return ResponseEntity.ok(chartData);
    }
}
