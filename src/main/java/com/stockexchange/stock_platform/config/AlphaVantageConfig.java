package com.stockexchange.stock_platform.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "alphavantage")
@Getter
@Setter
public class AlphaVantageConfig {
    private String apiKey;
    private String baseUrl;
    private long cacheTtl;
}