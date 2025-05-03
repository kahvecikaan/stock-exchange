package com.stockexchange.stock_platform.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "alpaca")
@Getter
@Setter
public class AlpacaConfig {
    private String apiKey;
    private String apiSecret;
    private String dataBaseUrl;
    private String paperBaseUrl;
    private String tradingBaseUrl;
    private String wsBaseUrl;
    private int maxRequestPerMinute = 200;
}
