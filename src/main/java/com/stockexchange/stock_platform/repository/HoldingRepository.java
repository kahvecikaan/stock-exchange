package com.stockexchange.stock_platform.repository;

import com.stockexchange.stock_platform.model.entity.Holding;
import com.stockexchange.stock_platform.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HoldingRepository extends JpaRepository<Holding, Long> {

    // Find all holdings for a specific user
    List<Holding> findByUser(User user);

    // Find all holdings for a specific user ID
    List<Holding> findByUserId(Long userId);

    // Find a specific holding by user and stock symbol
    Optional<Holding> findByUserAndSymbol(User user, String symbol);

    // Find a specific holding by user ID and stock symbol
    Optional<Holding> findByUserIdAndSymbol(Long userId, String symbol);

    // Find all holdings for a specific stock symbol (across all users)
    List<Holding> findBySymbol(String symbol);
}