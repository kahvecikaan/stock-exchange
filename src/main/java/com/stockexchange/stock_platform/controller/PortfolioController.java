package com.stockexchange.stock_platform.controller;

import com.stockexchange.stock_platform.dto.HoldingDto;
import com.stockexchange.stock_platform.dto.UserPortfolioDto;
import com.stockexchange.stock_platform.service.PortfolioAnalysisService;
import com.stockexchange.stock_platform.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/portfolios")
@RequiredArgsConstructor
@Slf4j
public class PortfolioController {

    private final PortfolioService portfolioService;
    private final PortfolioAnalysisService portfolioAnalysisService;

    @GetMapping("/{userId}")
    public ResponseEntity<UserPortfolioDto> getUserPortfolio(@PathVariable Long userId) {
        log.info("Fetching portfolio for user ID: {}", userId);
        UserPortfolioDto portfolio = portfolioService.getUserPortfolio(userId);
        return ResponseEntity.ok(portfolio);
    }

    @GetMapping("/{userId}/holdings/{symbol}")
    public ResponseEntity<HoldingDto> getUserHolding(
            @PathVariable Long userId,
            @PathVariable String symbol) {
        log.info("Fetching holding for user ID: {} and symbol: {}", userId, symbol);
        HoldingDto holdingDto = portfolioService.getHolding(userId, symbol);
        return ResponseEntity.ok(holdingDto);
    }

    @GetMapping("/{userId}/value")
    public ResponseEntity<BigDecimal> getPortfolioValue(@PathVariable Long userId) {
        log.info("Calculating portfolio value for user ID: {}", userId);
        BigDecimal value = portfolioService.calculatePortfolioValue(userId);
        return ResponseEntity.ok(value);
    }

    @GetMapping("/{userId}/analysis")
    public ResponseEntity<Map<String, Object>> analyzePortfolio(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "daily") String strategy) {
        log.info("Analyzing portfolio for user ID: {}, using strategy: {}", userId, strategy);
        Map<String, Object> analysis = portfolioAnalysisService.analyzePortfolio(userId, strategy);
        return ResponseEntity.ok(analysis);
    }

    @PostMapping("/{userId}/refresh")
    public ResponseEntity<Void> refreshPortfolio(@PathVariable Long userId) {
        log.info("Refreshing portfolio for user ID: {}", userId);
        portfolioService.updateHoldingPrices(userId);
        return ResponseEntity.ok().build();
    }
}
