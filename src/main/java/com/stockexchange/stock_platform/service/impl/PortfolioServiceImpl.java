package com.stockexchange.stock_platform.service.impl;

import com.stockexchange.stock_platform.dto.HoldingDto;
import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.dto.UserPortfolioDto;
import com.stockexchange.stock_platform.model.entity.Holding;
import com.stockexchange.stock_platform.model.entity.User;
import com.stockexchange.stock_platform.pattern.observer.StockPriceObserver;
import com.stockexchange.stock_platform.repository.HoldingRepository;
import com.stockexchange.stock_platform.repository.UserRepository;
import com.stockexchange.stock_platform.service.PortfolioService;
import com.stockexchange.stock_platform.service.StockPriceService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PortfolioServiceImpl implements PortfolioService, StockPriceObserver {

    private final HoldingRepository holdingRepository;
    private final UserRepository userRepository;
    private final StockPriceService stockPriceService;
    private final Map<String, StockPriceDto> latestPrices = new HashMap<>();

    public PortfolioServiceImpl(HoldingRepository holdingRepository,
                                UserRepository userRepository,
                                StockPriceServiceImpl stockPriceService) {
        this.holdingRepository = holdingRepository;
        this.userRepository = userRepository;
        this.stockPriceService = stockPriceService;
    }

    @PostConstruct
    public void init() {
        // Register as an observer
        if (stockPriceService instanceof StockPriceServiceImpl) {
            ((StockPriceServiceImpl) stockPriceService).registerObserver(this);
        }
    }

    @Override
    public void update(StockPriceDto stockPrice) {
        // Update the latest price cache
        latestPrices.put(stockPrice.getSymbol(), stockPrice);

        // Find all holdings for this symbol and update their current values
        List<Holding> affectedHoldings = holdingRepository.findBySymbol(stockPrice.getSymbol());
        for (Holding holding : affectedHoldings) {
            // In a real system, you might want to notify users of significant price changes
            // or update calculated fields in the database
            System.out.println("Holding updated: User " + holding.getUser().getId() +
                    " holding " + holding.getSymbol() + " new price: " + stockPrice.getPrice());
        }
    }

    @Override
    public UserPortfolioDto getUserPortfolio(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        List<HoldingDto> holdingDtos = getUserHoldings(userId);

        BigDecimal portfolioValue = holdingDtos.stream()
                .map(HoldingDto::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return UserPortfolioDto.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .cashBalance(user.getCashBalance())
                .portfolioValue(portfolioValue)
                .totalValue(user.getCashBalance().add(portfolioValue))
                .holdings(holdingDtos)
                .build();
    }

    @Override
    public HoldingDto getHolding(Long userId, String symbol) {
        Holding holding = holdingRepository.findByUserIdAndSymbol(userId, symbol)
                .orElseThrow(() -> new IllegalArgumentException("Holding not found"));

        return convertToDto(holding);
    }

    @Override
    public List<HoldingDto> getUserHoldings(Long userId) {
        List<Holding> holdings = holdingRepository.findByUserId(userId);
        return holdings.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void updateHoldingPrices(Long userId) {
        List<Holding> holdings = holdingRepository.findByUserId(userId);

        for (Holding holding : holdings) {
            StockPriceDto currentPrice = getCurrentPrice(holding.getSymbol());
            // In a real application, you might want to update a "currentPrice" field
            // or calculate and cache the current value
        }
    }

    @Override
    public BigDecimal calculatePortfolioValue(Long userId) {
        List<HoldingDto> holdings = getUserHoldings(userId);

        return holdings.stream()
                .map(HoldingDto::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private HoldingDto convertToDto(Holding holding) {
        StockPriceDto currentPrice = getCurrentPrice(holding.getSymbol());
        BigDecimal currentValue = holding.getQuantity().multiply(currentPrice.getPrice());
        BigDecimal investmentValue = holding.getQuantity().multiply(holding.getAvgPrice());
        BigDecimal profitLoss = currentValue.subtract(investmentValue);
        BigDecimal profitLossPercentage = investmentValue.compareTo(BigDecimal.ZERO) > 0
                ? profitLoss.multiply(new BigDecimal("100")).divide(investmentValue, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return HoldingDto.builder()
                .id(holding.getId())
                .symbol(holding.getSymbol())
                .quantity(holding.getQuantity())
                .avgPrice(holding.getAvgPrice())
                .currentPrice(currentPrice.getPrice())
                .currentValue(currentValue)
                .profitLoss(profitLoss)
                .profitLossPercentage(profitLossPercentage)
                .build();
    }

    private StockPriceDto getCurrentPrice(String symbol) {
        // Check our cache first
        StockPriceDto cachedPrice = latestPrices.get(symbol);
        if (cachedPrice != null) {
            return cachedPrice;
        }

        // If not in cache, fetch from service
        StockPriceDto price = stockPriceService.getCurrentPrice(symbol);
        latestPrices.put(symbol, price);
        return price;
    }
}