package com.stockexchange.stock_platform.service;

import com.stockexchange.stock_platform.dto.WatchlistItemDto;

import java.util.List;

public interface WatchlistService {
    List<WatchlistItemDto> getUserWatchlist(Long userId);
    WatchlistItemDto addToWatchlist(Long userId, String symbol);
    void removeFromWatchlist(Long userId, String symbol);
    List<String> getWatchlistSymbols(Long userId);
}