package com.stockexchange.stock_platform.service;

import java.util.Map;

public interface PortfolioAnalysisService {
    Map<String, Object> analyzePortfolio(Long userId, String strategyName);
}