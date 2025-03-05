package com.stockexchange.stock_platform.service.impl;

import com.stockexchange.stock_platform.dto.TransactionDto;
import com.stockexchange.stock_platform.exception.InsufficientFundsException;
import com.stockexchange.stock_platform.exception.InsufficientSharesException;
import com.stockexchange.stock_platform.model.entity.Holding;
import com.stockexchange.stock_platform.model.entity.Transaction;
import com.stockexchange.stock_platform.model.entity.User;
import com.stockexchange.stock_platform.model.enums.TransactionType;
import com.stockexchange.stock_platform.repository.HoldingRepository;
import com.stockexchange.stock_platform.repository.TransactionRepository;
import com.stockexchange.stock_platform.repository.UserRepository;
import com.stockexchange.stock_platform.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository,
                           HoldingRepository holdingRepository,
                           TransactionRepository transactionRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public User createUser(String username, String email, String password) {
        log.info("Creating new user with username: {}", username);

        // Check if username or email already exists
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists");
        }

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already exists");
        }

        // Create new user
        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .cashBalance(new BigDecimal("10000.00")) // Default starting cash
                .build();

        return userRepository.save(user);
    }

    @Override
    public User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));
    }

    @Override
    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found with username: " + username));
    }

    @Override
    public BigDecimal getUserCashBalance(Long userId) {
        User user = getUserById(userId);
        return user.getCashBalance();
    }

    @Override
    @Transactional
    public void updateCashBalance(Long userId, BigDecimal amount) {
        User user = getUserById(userId);
        BigDecimal newBalance = user.getCashBalance().add(amount);

        // Check for negative balance if it's a withdrawal
        if (amount.compareTo(BigDecimal.ZERO) < 0 && newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientFundsException("Insufficient funds for this operation");
        }

        user.setCashBalance(newBalance);
        userRepository.save(user);

        log.info("Updated cash balance for user {}: {} (change: {})", userId, newBalance, amount);
    }

    @Override
    public List<TransactionDto> getUserTransactions(Long userId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "executionTime"));

        Page<Transaction> transactions = transactionRepository.findByUserId(userId, pageRequest);
        return transactions.getContent().stream()
                .map(this::convertToTransactionDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<TransactionDto> getUserTransactionsBySymbol(Long userId, String symbol) {
        Page<Transaction> transactions = transactionRepository.findByUserIdAndSymbol(
                userId, symbol, PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "executionTime")));

        return transactions.getContent().stream()
                .map(this::convertToTransactionDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<TransactionDto> getUserTransactionsByDateRange(Long userId, LocalDateTime startDate, LocalDateTime endDate) {
        List<Transaction> transactions = transactionRepository.findByUserIdAndExecutionTimeBetween(
                userId, startDate, endDate);

        return transactions.stream()
                .map(this::convertToTransactionDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public TransactionDto recordTransaction(Long userId, String symbol, TransactionType type,
                                            BigDecimal quantity, BigDecimal price) {
        User user = getUserById(userId);
        BigDecimal totalAmount = quantity.multiply(price);

        // Find or create holding
        Holding holding = holdingRepository.findByUserIdAndSymbol(userId, symbol)
                .orElse(Holding.builder()
                        .user(user)
                        .symbol(symbol)
                        .quantity(BigDecimal.ZERO)
                        .avgPrice(BigDecimal.ZERO)
                        .build());

        // Create transaction
        Transaction transaction = Transaction.builder()
                .user(user)
                .holding(holding)
                .type(type)
                .symbol(symbol)
                .quantity(quantity)
                .price(price)
                .totalAmount(totalAmount)
                .executionTime(LocalDateTime.now())
                .build();

        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Recorded {} transaction for user {}: {} {} at {} (total: {})",
                type, userId, quantity, symbol, price, totalAmount);

        return convertToTransactionDto(savedTransaction);
    }

    @Override
    public boolean hasEnoughShares(Long userId, String symbol, BigDecimal quantity) {
        return holdingRepository.findByUserIdAndSymbol(userId, symbol)
                .map(holding -> holding.getQuantity().compareTo(quantity) >= 0)
                .orElse(false);
    }

    private TransactionDto convertToTransactionDto(Transaction transaction) {
        return TransactionDto.builder()
                .id(transaction.getId())
                .userId(transaction.getUser().getId())
                .type(transaction.getType())
                .symbol(transaction.getSymbol())
                .quantity(transaction.getQuantity())
                .price(transaction.getPrice())
                .totalAmount(transaction.getTotalAmount())
                .executionTime(transaction.getExecutionTime())
                .build();
    }
}