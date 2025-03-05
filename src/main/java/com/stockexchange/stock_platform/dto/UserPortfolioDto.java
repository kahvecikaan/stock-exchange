package com.stockexchange.stock_platform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPortfolioDto {
    private Long userId;
    private String username;
    private BigDecimal cashBalance;
    private BigDecimal portfolioValue;
    private BigDecimal totalValue;
    private List<HoldingDto> holdings;
}