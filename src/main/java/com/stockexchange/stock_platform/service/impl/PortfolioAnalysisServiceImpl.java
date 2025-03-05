package com.stockexchange.stock_platform.service.impl;

import com.stockexchange.stock_platform.model.entity.User;
import com.stockexchange.stock_platform.pattern.strategy.PortfolioAnalysisStrategy;
import com.stockexchange.stock_platform.repository.UserRepository;
import com.stockexchange.stock_platform.service.PortfolioAnalysisService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PortfolioAnalysisServiceImpl implements PortfolioAnalysisService {

    private final UserRepository userRepository;
    private final Map<String, PortfolioAnalysisStrategy> strategies = new HashMap<>();

    public PortfolioAnalysisServiceImpl(UserRepository userRepository,
                                        List<PortfolioAnalysisStrategy> strategyList) {
        this.userRepository = userRepository;

        // Register all available strategies
        for (PortfolioAnalysisStrategy strategy : strategyList) {
            strategies.put(strategy.getName(), strategy);
        }
    }

    @Override
    public Map<String, Object> analyzePortfolio(Long userId, String strategyName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Get the requested strategy or default to daily
        PortfolioAnalysisStrategy strategy = strategies.getOrDefault(
                strategyName, strategies.get("daily"));

        if (strategy == null) {
            throw new IllegalArgumentException("No analysis strategies available");
        }

        // Apply the strategy to analyze the portfolio
        return strategy.analyze(user);
    }
}