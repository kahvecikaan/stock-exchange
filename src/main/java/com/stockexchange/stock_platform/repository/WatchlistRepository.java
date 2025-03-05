package com.stockexchange.stock_platform.repository;

import com.stockexchange.stock_platform.model.entity.User;
import com.stockexchange.stock_platform.model.entity.WatchlistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WatchlistRepository extends JpaRepository<WatchlistItem, Long> {

    // Find all watchlist items for a specific user
    List<WatchlistItem> findByUser(User user);

    // Find all watchlist items for a specific user ID
    List<WatchlistItem> findByUserId(Long userId);

    // Find a specific watchlist item by user and symbol
    Optional<WatchlistItem> findByUserAndSymbol(User user, String symbol);

    // Find a specific watchlist item by user ID and symbol
    Optional<WatchlistItem> findByUserIdAndSymbol(Long userId, String symbol);

    // Check if a symbol exists in a user's watchlist
    boolean existsByUserAndSymbol(User user, String symbol);

    // Check if a symbol exists in a user's watchlist using user ID
    boolean existsByUserIdAndSymbol(Long userId, String symbol);

    // Find all users watching a specific symbol
    List<WatchlistItem> findBySymbol(String symbol);
}