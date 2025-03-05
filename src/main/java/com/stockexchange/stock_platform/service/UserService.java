package com.stockexchange.stock_platform.service;

import com.stockexchange.stock_platform.dto.TransactionDto;
import com.stockexchange.stock_platform.model.entity.User;
import com.stockexchange.stock_platform.model.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface UserService {
    User createUser(String username, String email, String password);
    User getUserById(Long userId);
    User getUserByUsername(String username);
    BigDecimal getUserCashBalance(Long userId);
    void updateCashBalance(Long userId, BigDecimal amount);
    List<TransactionDto> getUserTransactions(Long userId, int page, int size);
    List<TransactionDto> getUserTransactionsBySymbol(Long userId, String symbol);
    List<TransactionDto> getUserTransactionsByDateRange(Long userId, LocalDateTime startDate, LocalDateTime endDate);
    TransactionDto recordTransaction(Long userId, String symbol, TransactionType type, BigDecimal quantity, BigDecimal price);
    boolean hasEnoughShares(Long userId, String symbol, BigDecimal quantity);
}