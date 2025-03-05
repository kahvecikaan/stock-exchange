package com.stockexchange.stock_platform.service.impl;

import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.dto.WatchlistItemDto;
import com.stockexchange.stock_platform.model.entity.User;
import com.stockexchange.stock_platform.model.entity.WatchlistItem;
import com.stockexchange.stock_platform.repository.UserRepository;
import com.stockexchange.stock_platform.repository.WatchlistRepository;
import com.stockexchange.stock_platform.service.StockPriceService;
import com.stockexchange.stock_platform.service.WatchlistService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class WatchlistServiceImpl implements WatchlistService {

    private final WatchlistRepository watchlistRepository;
    private final UserRepository userRepository;
    private final StockPriceService stockPriceService;

    public WatchlistServiceImpl(WatchlistRepository watchlistRepository,
                                UserRepository userRepository,
                                StockPriceService stockPriceService) {
        this.watchlistRepository = watchlistRepository;
        this.userRepository = userRepository;
        this.stockPriceService = stockPriceService;
    }

    @Override
    public List<WatchlistItemDto> getUserWatchlist(Long userId) {
        List<WatchlistItem> watchlist = watchlistRepository.findByUserId(userId);
        return watchlist.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public WatchlistItemDto addToWatchlist(Long userId, String symbol) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Check if already in watchlist
        if (watchlistRepository.existsByUserIdAndSymbol(userId, symbol)) {
            throw new IllegalArgumentException("Symbol already in watchlist");
        }

        // Add to watchlist
        WatchlistItem watchlistItem = WatchlistItem.builder()
                .user(user)
                .symbol(symbol)
                .addedAt(LocalDateTime.now())
                .build();

        WatchlistItem savedItem = watchlistRepository.save(watchlistItem);
        return convertToDto(savedItem);
    }

    @Override
    @Transactional
    public void removeFromWatchlist(Long userId, String symbol) {
        WatchlistItem item = watchlistRepository.findByUserIdAndSymbol(userId, symbol)
                .orElseThrow(() -> new IllegalArgumentException("Symbol not found in watchlist"));

        watchlistRepository.delete(item);
    }

    @Override
    public List<String> getWatchlistSymbols(Long userId) {
        return watchlistRepository.findByUserId(userId).stream()
                .map(WatchlistItem::getSymbol)
                .collect(Collectors.toList());
    }

    private WatchlistItemDto convertToDto(WatchlistItem item) {
        // Get current price for the symbol
        StockPriceDto priceData = stockPriceService.getCurrentPrice(item.getSymbol());

        return WatchlistItemDto.builder()
                .id(item.getId())
                .userId(item.getUser().getId())
                .symbol(item.getSymbol())
                .currentPrice(priceData.getPrice())
                .change(priceData.getChange())
                .changePercent(priceData.getChangePercent())
                .addedAt(item.getAddedAt())
                .build();
    }
}