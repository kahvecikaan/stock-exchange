package com.stockexchange.stock_platform.controller;

import com.stockexchange.stock_platform.dto.WatchlistItemDto;
import com.stockexchange.stock_platform.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.parameters.P;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("api/watchlists")
@RequiredArgsConstructor
@Slf4j
public class WatchlistController {

    private final WatchlistService watchlistService;

    @GetMapping("/{userId}")
    public ResponseEntity<List<WatchlistItemDto>> getUserWatchlist(@PathVariable Long userId) {
        log.info("Fetching watchlist for user ID: {}", userId);
        List<WatchlistItemDto> watchlist = watchlistService.getUserWatchlist(userId);

        return ResponseEntity.ok(watchlist);
    }

    @PostMapping("/{userId}/{symbol}")
    public ResponseEntity<WatchlistItemDto> addToWatchlist(
            @PathVariable Long userId,
            @PathVariable String symbol) {
        log.info("Adding {} to watchlist for user ID: {}", symbol, userId);
        WatchlistItemDto item = watchlistService.addToWatchlist(userId, symbol);

        return ResponseEntity.status(HttpStatus.CREATED).body(item);
    }

    @DeleteMapping("/{userId}/{symbol}")
    public ResponseEntity<Void> removeFromWatchlist(
            @PathVariable Long userId,
            @PathVariable String symbol) {
        log.info("Removing {} from watchlist for user ID: {}", symbol, userId);
        watchlistService.removeFromWatchlist(userId, symbol);

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}/symbols")
    public ResponseEntity<List<String>> getWatchlistSymbols(@PathVariable Long userId) {
        log.info("Fetching watchlist symbols for user ID: {}", userId);
        List<String> symbols = watchlistService.getWatchlistSymbols(userId);

        return ResponseEntity.ok(symbols);
    }
}
