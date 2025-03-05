package com.stockexchange.stock_platform.service;

import com.stockexchange.stock_platform.dto.HoldingDto;
import com.stockexchange.stock_platform.dto.UserPortfolioDto;

import java.math.BigDecimal;
import java.util.List;

public interface PortfolioService {
    UserPortfolioDto getUserPortfolio(Long userId);
    HoldingDto getHolding(Long userId, String symbol);
    List<HoldingDto> getUserHoldings(Long userId);
    void updateHoldingPrices(Long userId);
    BigDecimal calculatePortfolioValue(Long userId);
}