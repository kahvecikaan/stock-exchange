package com.stockexchange.stock_platform.pattern.strategy;

import com.stockexchange.stock_platform.model.entity.User;

import java.util.Map;

public interface PortfolioAnalysisStrategy {
    Map<String, Object> analyze(User user);
    String getName();
}