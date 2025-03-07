package com.stockexchange.stock_platform.service.api;

import com.stockexchange.stock_platform.config.AlphaVantageConfig;
import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.exception.ExternalApiException;
import com.stockexchange.stock_platform.util.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class AlphaVantageClient {

    private final RestTemplate restTemplate;
    private final AlphaVantageConfig config;
    private final Map<String, StockPriceDto> priceCache = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> cacheTimestamps = new ConcurrentHashMap<>();
    private final RateLimiter rateLimiter;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 2000;

    public AlphaVantageClient(
            RestTemplate restTemplate,
            AlphaVantageConfig config,
            @Value("${alphavantage.max-requests-per-minute:5}") int maxRequestsPerMinute) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.rateLimiter = new RateLimiter(maxRequestsPerMinute);
        log.info("AlphaVantageClient initialized with max {} requests per minute", maxRequestsPerMinute);
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

        Map<String, Object> params = new HashMap<>();
        params.put("function", "GLOBAL_QUOTE");
        params.put("symbol", symbol);

        try {
            Map<String, Object> responseBody = executeApiCall(params);
            Map<String, String> globalQuote = (Map<String, String>) responseBody.get("Global Quote");

            if (globalQuote == null || globalQuote.isEmpty()) {
                throw new ExternalApiException("Invalid response format from Alpha Vantage");
            }

            // Parse the data from Global Quote response
            StockPriceDto stockPrice = StockPriceDto.builder()
                    .symbol(symbol)
                    .price(parseBigDecimal(globalQuote.get("05. price")))
                    .open(parseBigDecimal(globalQuote.get("02. open")))
                    .high(parseBigDecimal(globalQuote.get("03. high")))
                    .low(parseBigDecimal(globalQuote.get("04. low")))
                    .volume(parseLong(globalQuote.get("06. volume")))
                    .change(parseBigDecimal(globalQuote.get("09. change")))
                    .changePercent(parseBigDecimal(globalQuote.get("10. change percent").replace("%", "")))
                    .timestamp(LocalDateTime.now())
                    .build();

            // Update cache
            updateCache(symbol, stockPrice);

            return stockPrice;
        } catch (Exception e) {
            log.error("Error fetching stock price for {}: {}", symbol, e.getMessage());
            throw handleApiException("Error fetching stock price", e);
        }
    }

    /**
     * Gets historical daily price data using the TIME_SERIES_DAILY endpoint.
     */
    public List<StockPriceDto> getHistoricalDailyPrices(String symbol, int days) {
        log.info("Fetching historical daily prices for {} (past {} days)", symbol, days);

        Map<String, Object> params = new HashMap<>();
        params.put("function", "TIME_SERIES_DAILY");
        params.put("symbol", symbol);
        params.put("outputsize", days > 100 ? "full" : "compact");

        try {
            Map<String, Object> responseBody = executeApiCall(params);
            Map<String, Map<String, String>> timeSeries =
                    (Map<String, Map<String, String>>) responseBody.get("Time Series (Daily)");

            if (timeSeries == null || timeSeries.isEmpty()) {
                throw new ExternalApiException("Invalid response format from Alpha Vantage");
            }

            return parseTimeSeriesData(symbol, timeSeries, days);
        } catch (Exception e) {
            log.error("Error fetching historical prices for {}: {}", symbol, e.getMessage());
            throw handleApiException("Error fetching historical prices", e);
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

        Map<String, Object> params = new HashMap<>();
        params.put("function", "TIME_SERIES_INTRADAY");
        params.put("symbol", symbol);
        params.put("interval", interval);
        params.put("outputsize", "compact");

        try {
            Map<String, Object> responseBody = executeApiCall(params);
            String timeSeriesKey = "Time Series (" + interval + ")";
            Map<String, Map<String, String>> timeSeries =
                    (Map<String, Map<String, String>>) responseBody.get(timeSeriesKey);

            if (timeSeries == null || timeSeries.isEmpty()) {
                throw new ExternalApiException("Invalid response format from Alpha Vantage");
            }

            return parseIntradayTimeSeriesData(symbol, timeSeries);
        } catch (Exception e) {
            log.error("Error fetching intraday prices for {}: {}", symbol, e.getMessage());
            throw handleApiException("Error fetching intraday prices", e);
        }
    }

    /**
     * Performs a symbol search using the SYMBOL_SEARCH endpoint.
     */
    public List<Map<String, String>> searchSymbol(String keywords) {
        log.info("Searching for symbols matching: {}", keywords);

        Map<String, Object> params = new HashMap<>();
        params.put("function", "SYMBOL_SEARCH");
        params.put("keywords", keywords);

        try {
            Map<String, Object> responseBody = executeApiCall(params);
            List<Map<String, String>> matches = (List<Map<String, String>>) responseBody.get("bestMatches");

            if (matches == null) {
                return new ArrayList<>();
            }

            return matches;
        } catch (Exception e) {
            log.error("Error searching for symbols: {}", e.getMessage());
            throw handleApiException("Error searching for symbols", e);
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

    // ---- Private helper methods ----

    /**
     * Executes an API call with rate limiting and retry logic
     */
    private Map<String, Object> executeApiCall(Map<String, Object> params) {
        int attempts = 0;
        while (attempts < MAX_RETRY_ATTEMPTS) {
            attempts++;

            // Wait for rate limit if needed
            if (!rateLimiter.acquire()) {
                throw new ExternalApiException("Rate limiter interrupted");
            }

            try {
                String url = buildApiUrl(params);
                log.debug("Making API call to: {}", url);

                ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new ExternalApiException("Failed to fetch data: " + response.getStatusCode());
                }

                // Check for API error responses in the body (Alpha Vantage returns 200 OK with error messages)
                Map<String, Object> responseBody = response.getBody();
                if (responseBody.containsKey("Error Message") || responseBody.containsKey("Information")) {
                    String errorMsg = (String) responseBody.getOrDefault("Error Message",
                            responseBody.getOrDefault("Information", "Unknown API error"));
                    log.warn("API returned error: {}", errorMsg);

                    if (errorMsg.contains("API call frequency") && attempts < MAX_RETRY_ATTEMPTS) {
                        log.info("API rate limit hit, retrying after delay (attempt {}/{})",
                                attempts, MAX_RETRY_ATTEMPTS);
                        Thread.sleep(RETRY_DELAY_MS * attempts);
                        continue;
                    }

                    throw new ExternalApiException("Alpha Vantage API error: " + errorMsg);
                }

                return responseBody;
            } catch (HttpClientErrorException | HttpServerErrorException e) {
                // HTTP errors
                if (shouldRetry(e.getStatusCode(), attempts)) {
                    log.warn("HTTP error {}, retrying (attempt {}/{})",
                            e.getStatusCode(), attempts, MAX_RETRY_ATTEMPTS);
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempts);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ExternalApiException("Interrupted during retry delay", ie);
                    }
                } else {
                    throw handleApiException("HTTP error from API", e);
                }
            } catch (ResourceAccessException e) {
                // Network errors
                if (attempts < MAX_RETRY_ATTEMPTS) {
                    log.warn("Network error, retrying (attempt {}/{})", attempts, MAX_RETRY_ATTEMPTS);
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempts);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ExternalApiException("Interrupted during retry delay", ie);
                    }
                } else {
                    throw handleApiException("Network error accessing API", e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExternalApiException("Interrupted during retry delay", e);
            } catch (Exception e) {
                throw handleApiException("Error executing API call", e);
            }
        }

        throw new ExternalApiException("Maximum retry attempts reached");
    }

    /**
     * Builds the API URL with parameters
     */
    private String buildApiUrl(Map<String, Object> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(config.getBaseUrl());

        // Add all params from the map
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            builder.queryParam(entry.getKey(), entry.getValue());
        }

        // Always add API key
        builder.queryParam("apikey", config.getApiKey());

        return builder.build().toUriString();
    }

    /**
     * Parses time series data from the daily endpoint
     */
    private List<StockPriceDto> parseTimeSeriesData(String symbol,
                                                    Map<String, Map<String, String>> timeSeries,
                                                    int days) {
        List<StockPriceDto> historicalPrices = new ArrayList<>();

        // Parse each day's data
        for (Map.Entry<String, Map<String, String>> entry : timeSeries.entrySet()) {
            if (historicalPrices.size() >= days) {
                break;
            }

            String dateStr = entry.getKey();
            Map<String, String> dailyData = entry.getValue();

            try {
                // Parse date (format is YYYY-MM-DD)
                LocalDateTime date = LocalDateTime.parse(dateStr + " 00:00:00", DATE_TIME_FORMATTER);

                StockPriceDto priceData = StockPriceDto.builder()
                        .symbol(symbol)
                        .timestamp(date)
                        .open(parseBigDecimal(dailyData.get("1. open")))
                        .high(parseBigDecimal(dailyData.get("2. high")))
                        .low(parseBigDecimal(dailyData.get("3. low")))
                        .price(parseBigDecimal(dailyData.get("4. close"))) // Close price as the main price
                        .volume(parseLong(dailyData.get("5. volume")))
                        .build();

                historicalPrices.add(priceData);
            } catch (DateTimeParseException e) {
                log.warn("Failed to parse date: {}", dateStr, e);
            } catch (Exception e) {
                log.warn("Failed to parse data for date {}: {}", dateStr, e.getMessage());
            }
        }

        // Sort by date, newest first
        historicalPrices.sort(Comparator.comparing(StockPriceDto::getTimestamp).reversed());

        return historicalPrices;
    }

    /**
     * Parses time series data from the intraday endpoint
     */
    private List<StockPriceDto> parseIntradayTimeSeriesData(String symbol,
                                                            Map<String, Map<String, String>> timeSeries) {
        List<StockPriceDto> intradayPrices = new ArrayList<>();

        // Parse each time point's data
        for (Map.Entry<String, Map<String, String>> entry : timeSeries.entrySet()) {
            String dateTimeStr = entry.getKey();
            Map<String, String> pricePoint = entry.getValue();

            try {
                // Parse datetime
                LocalDateTime dateTime = LocalDateTime.parse(dateTimeStr, DATE_TIME_FORMATTER);

                StockPriceDto priceData = StockPriceDto.builder()
                        .symbol(symbol)
                        .timestamp(dateTime)
                        .open(parseBigDecimal(pricePoint.get("1. open")))
                        .high(parseBigDecimal(pricePoint.get("2. high")))
                        .low(parseBigDecimal(pricePoint.get("3. low")))
                        .price(parseBigDecimal(pricePoint.get("4. close"))) // Close price as the main price
                        .volume(parseLong(pricePoint.get("5. volume")))
                        .build();

                intradayPrices.add(priceData);
            } catch (DateTimeParseException e) {
                log.warn("Failed to parse datetime: {}", dateTimeStr, e);
            } catch (Exception e) {
                log.warn("Failed to parse data for datetime {}: {}", dateTimeStr, e.getMessage());
            }
        }

        // Sort by timestamp, newest first
        intradayPrices.sort(Comparator.comparing(StockPriceDto::getTimestamp).reversed());

        return intradayPrices;
    }

    /**
     * Updates the price cache
     */
    private void updateCache(String symbol, StockPriceDto stockPrice) {
        priceCache.put(symbol, stockPrice);
        cacheTimestamps.put(symbol, LocalDateTime.now());
    }

    /**
     * Determines if a request should be retried based on the HTTP status
     */
    private boolean shouldRetry(HttpStatusCode status, int currentAttempt) {
        // Retry on server errors and some client errors if we haven't reached max attempts
        return currentAttempt < MAX_RETRY_ATTEMPTS &&
                (status.is5xxServerError() ||
                        status.value() == HttpStatus.TOO_MANY_REQUESTS.value() ||
                        status.value() == HttpStatus.REQUEST_TIMEOUT.value());
    }

    /**
     * Handles exceptions from API calls and wraps them in ExternalApiException
     */
    private ExternalApiException handleApiException(String message, Exception e) {
        if (e instanceof ExternalApiException) {
            return (ExternalApiException) e;
        }

        return new ExternalApiException(message + ": " + e.getMessage(), e);
    }

    /**
     * Safely parses BigDecimal from string
     */
    private BigDecimal parseBigDecimal(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (Exception e) {
            log.warn("Error parsing decimal value: {}", value);
            return BigDecimal.ZERO;
        }
    }

    /**
     * Safely parses Long from string
     */
    private Long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            log.warn("Error parsing long value: {}", value);
            return 0L;
        }
    }
}