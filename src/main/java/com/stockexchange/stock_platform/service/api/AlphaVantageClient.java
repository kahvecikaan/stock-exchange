package com.stockexchange.stock_platform.service.api;

import com.stockexchange.stock_platform.config.AlphaVantageConfig;
import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.exception.ExternalApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class AlphaVantageClient {

    private final RestTemplate restTemplate;
    private final AlphaVantageConfig config;
    private final Map<String, StockPriceDto> priceCache = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> cacheTimestamps = new ConcurrentHashMap<>();

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public AlphaVantageClient(RestTemplate restTemplate, AlphaVantageConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    /**
     * Gets the current stock price using the GLOBAL_QUOTE endpoint.
     * Uses a local cache with configurable TTL to minimize API calls.
     */
    public StockPriceDto getCurrentStockPrice(String symbol) {
        // Check cache first
        if (priceCache.containsKey(symbol)) {
            LocalDateTime cacheTime = cacheTimestamps.get(symbol);
            long cacheTtlMillis = config.getCacheTtl();

            // If cache hasn't expired, return cached value
            if (cacheTime.plusNanos(cacheTtlMillis * 1_000_000).isAfter(LocalDateTime.now())) {
                log.debug("Returning cached price for {}", symbol);
                return priceCache.get(symbol);
            }
        }

        log.info("Fetching current price for {} from Alpha Vantage", symbol);

        // Build the URL for Alpha Vantage Global Quote API
        String url = UriComponentsBuilder.fromHttpUrl(config.getBaseUrl())
                .queryParam("function", "GLOBAL_QUOTE")
                .queryParam("symbol", symbol)
                .queryParam("apikey", config.getApiKey())
                .build()
                .toUriString();

        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new ExternalApiException("Failed to fetch stock data: " + response.getStatusCode());
            }

            Map<String, Object> responseBody = response.getBody();
            Map<String, String> globalQuote = (Map<String, String>) responseBody.get("Global Quote");

            if (globalQuote == null || globalQuote.isEmpty()) {
                throw new ExternalApiException("Invalid response format from Alpha Vantage");
            }

            // Parse the data from Global Quote response
            StockPriceDto stockPrice = StockPriceDto.builder()
                    .symbol(symbol)
                    .price(new BigDecimal(globalQuote.get("05. price")))
                    .open(new BigDecimal(globalQuote.get("02. open")))
                    .high(new BigDecimal(globalQuote.get("03. high")))
                    .low(new BigDecimal(globalQuote.get("04. low")))
                    .volume(Long.parseLong(globalQuote.get("06. volume")))
                    .change(new BigDecimal(globalQuote.get("09. change")))
                    .changePercent(new BigDecimal(globalQuote.get("10. change percent").replace("%", "")))
                    .timestamp(LocalDateTime.now())
                    .build();

            // Update cache
            priceCache.put(symbol, stockPrice);
            cacheTimestamps.put(symbol, LocalDateTime.now());

            return stockPrice;
        } catch (Exception e) {
            log.error("Error fetching stock price for {}: {}", symbol, e.getMessage());
            throw new ExternalApiException("Error fetching stock price: " + e.getMessage(), e);
        }
    }

    /**
     * Gets historical daily price data using the TIME_SERIES_DAILY endpoint.
     */
    public List<StockPriceDto> getHistoricalDailyPrices(String symbol, int days) {
        log.info("Fetching historical daily prices for {} (past {} days)", symbol, days);

        // Build the URL for Alpha Vantage Daily Time Series API
        String url = UriComponentsBuilder.fromHttpUrl(config.getBaseUrl())
                .queryParam("function", "TIME_SERIES_DAILY")
                .queryParam("symbol", symbol)
                .queryParam("outputsize", days > 100 ? "full" : "compact")
                .queryParam("apikey", config.getApiKey())
                .build()
                .toUriString();

        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new ExternalApiException("Failed to fetch historical data: " + response.getStatusCode());
            }

            Map<String, Object> responseBody = response.getBody();
            Map<String, Map<String, String>> timeSeries =
                    (Map<String, Map<String, String>>) responseBody.get("Time Series (Daily)");

            if (timeSeries == null || timeSeries.isEmpty()) {
                throw new ExternalApiException("Invalid response format from Alpha Vantage");
            }

            List<StockPriceDto> historicalPrices = new ArrayList<>();

            // Parse each day's data
            for (Map.Entry<String, Map<String, String>> entry : timeSeries.entrySet()) {
                String dateStr = entry.getKey();
                Map<String, String> dailyData = entry.getValue();

                // Parse date (format is YYYY-MM-DD)
                LocalDateTime date = LocalDateTime.parse(dateStr + " 00:00:00", DATE_TIME_FORMATTER);

                // Only include requested number of days
                if (historicalPrices.size() >= days) {
                    break;
                }

                StockPriceDto priceData = StockPriceDto.builder()
                        .symbol(symbol)
                        .timestamp(date)
                        .open(new BigDecimal(dailyData.get("1. open")))
                        .high(new BigDecimal(dailyData.get("2. high")))
                        .low(new BigDecimal(dailyData.get("3. low")))
                        .price(new BigDecimal(dailyData.get("4. close"))) // Close price as the main price
                        .volume(Long.parseLong(dailyData.get("5. volume")))
                        .build();

                historicalPrices.add(priceData);
            }

            // Sort by date, newest first
            historicalPrices.sort(Comparator.comparing(StockPriceDto::getTimestamp).reversed());

            return historicalPrices;
        } catch (Exception e) {
            log.error("Error fetching historical prices for {}: {}", symbol, e.getMessage());
            throw new ExternalApiException("Error fetching historical prices: " + e.getMessage(), e);
        }
    }

    /**
     * Gets intraday price data using the TIME_SERIES_INTRADAY endpoint.
     */
    public List<StockPriceDto> getIntradayPrices(String symbol, String interval) {
        log.info("Fetching intraday prices for {} with interval {}", symbol, interval);

        // Validate interval
        if (!Arrays.asList("1min", "5min", "15min", "30min", "60min").contains(interval)) {
            throw new IllegalArgumentException("Invalid interval. Must be one of: 1min, 5min, 15min, 30min, 60min");
        }

        // Build the URL for Alpha Vantage Intraday Time Series API
        String url = UriComponentsBuilder.fromHttpUrl(config.getBaseUrl())
                .queryParam("function", "TIME_SERIES_INTRADAY")
                .queryParam("symbol", symbol)
                .queryParam("interval", interval)
                .queryParam("outputsize", "compact")
                .queryParam("apikey", config.getApiKey())
                .build()
                .toUriString();

        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new ExternalApiException("Failed to fetch intraday data: " + response.getStatusCode());
            }

            Map<String, Object> responseBody = response.getBody();
            String timeSeriesKey = "Time Series (" + interval + ")";
            Map<String, Map<String, String>> timeSeries =
                    (Map<String, Map<String, String>>) responseBody.get(timeSeriesKey);

            if (timeSeries == null || timeSeries.isEmpty()) {
                throw new ExternalApiException("Invalid response format from Alpha Vantage");
            }

            List<StockPriceDto> intradayPrices = new ArrayList<>();

            // Parse each time point's data
            for (Map.Entry<String, Map<String, String>> entry : timeSeries.entrySet()) {
                String dateTimeStr = entry.getKey();
                Map<String, String> pricePoint = entry.getValue();

                // Parse datetime
                LocalDateTime dateTime = LocalDateTime.parse(dateTimeStr, DATE_TIME_FORMATTER);

                StockPriceDto priceData = StockPriceDto.builder()
                        .symbol(symbol)
                        .timestamp(dateTime)
                        .open(new BigDecimal(pricePoint.get("1. open")))
                        .high(new BigDecimal(pricePoint.get("2. high")))
                        .low(new BigDecimal(pricePoint.get("3. low")))
                        .price(new BigDecimal(pricePoint.get("4. close"))) // Close price as the main price
                        .volume(Long.parseLong(pricePoint.get("5. volume")))
                        .build();

                intradayPrices.add(priceData);
            }

            // Sort by timestamp, newest first
            intradayPrices.sort(Comparator.comparing(StockPriceDto::getTimestamp).reversed());

            return intradayPrices;
        } catch (Exception e) {
            log.error("Error fetching intraday prices for {}: {}", symbol, e.getMessage());
            throw new ExternalApiException("Error fetching intraday prices: " + e.getMessage(), e);
        }
    }

    /**
     * Clears the price cache, forcing fresh data to be fetched on next request.
     */
    public void clearCache() {
        log.info("Clearing price cache");
        priceCache.clear();
        cacheTimestamps.clear();
    }
}
