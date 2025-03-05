package com.stockexchange.stock_platform.repository;

import com.stockexchange.stock_platform.model.entity.Transaction;
import com.stockexchange.stock_platform.model.entity.User;
import com.stockexchange.stock_platform.model.enums.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    // Find all transactions for a specific user with pagination
    Page<Transaction> findByUser(User user, Pageable pageable);

    // Find all transactions for a specific user ID with pagination
    Page<Transaction> findByUserId(Long userId, Pageable pageable);

    // Find all transactions for a specific symbol with pagination
    Page<Transaction> findBySymbol(String symbol, Pageable pageable);

    // Find transactions for a specific user and symbol with pagination
    Page<Transaction> findByUserAndSymbol(User user, String symbol, Pageable pageable);

    // Find transactions by type (BUY/SELL)
    List<Transaction> findByType(TransactionType type);

    // Find transactions for a user within a date range
    List<Transaction> findByUserAndExecutionTimeBetween(
            User user, LocalDateTime startDate, LocalDateTime endDate);

    // Find transactions for a user ID within a date range
    List<Transaction> findByUserIdAndExecutionTimeBetween(
            Long userId, LocalDateTime startDate, LocalDateTime endDate);

    Page<Transaction> findByUserIdAndSymbol(Long userId, String symbol, Pageable pageable);
}