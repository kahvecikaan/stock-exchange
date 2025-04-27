
package com.stockexchange.stock_platform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultDto {
    private String id;          // Asset ID from Alpaca
    private String symbol;      // Stock symbol (e.g., AAPL)
    private String name;        // Company name (e.g., Apple Inc. Common Stock)
    private String type;        // Asset class (e.g., us_equity)
    private String exchange;    // Exchange (e.g., NASDAQ)
    private String region;      // Region (e.g., US)
    private String currency;    // Currency (e.g., USD)
    private boolean tradable;   // Whether the asset is tradable
    private boolean fractionable; // Whether fractional shares are supported
}