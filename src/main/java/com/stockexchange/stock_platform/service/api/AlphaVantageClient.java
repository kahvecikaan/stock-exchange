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
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    // Timezone constants
    private static final ZoneId MARKET_TIMEZONE = ZoneId.of("America/New_York");
    private static final ZoneId UTC = ZoneId.of("UTC");

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
     * Gets the current stock price, using real-time data when the market is open.
     */
    public StockPriceDto getCurrentStockPrice(String symbol) {
        // Check cache first
        if (priceCache.containsKey(symbol)) {
            LocalDateTime cacheTime = cacheTimestamps.get(symbol);
            long cacheTtlMillis = config.getCacheTtl();

            // Use a shorter cache time during market hours
            long effectiveCacheTtl = isDuringMarketHours() ? Math.min(cacheTtlMillis, 60_000) : cacheTtlMillis;

            // If cache hasn't expired, return cached value
            if (cacheTime.plusNanos(effectiveCacheTtl * 1_000_000).isAfter(LocalDateTime.now())) {
                log.debug("Returning cached price for {}", symbol);
                return priceCache.get(symbol);
            }
        }

        log.info("Fetching current price for {} from Alpha Vantage", symbol);

        try {
            // Check if market is currently open
            boolean marketOpen = isDuringMarketHours();
            StockPriceDto stockPrice;

            if (marketOpen) {
                // During market hours, use intraday data for the most current price
                stockPrice = getMostRecentIntradayPrice(symbol);
            } else {
                // Outside market hours, use the global quote for the latest closing price
                stockPrice = getLatestDailyPrice(symbol);
            }

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

        // Get current year-month for targeting data efficiently
        // String yearMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        Map<String, Object> params = new HashMap<>();
        params.put("function", "TIME_SERIES_INTRADAY");
        params.put("symbol", symbol);
        params.put("interval", interval);
        // params.put("month", yearMonth);
        params.put("outputsize", "full");

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
     * Gets weekly price data using the TIME_SERIES_WEEKLY endpoint
     */
    public List<StockPriceDto> getWeeklyPrices(String symbol) {
        log.info("Fetching weekly prices for {}", symbol);
        Map<String, Object> params = new HashMap<>();
        params.put("function", "TIME_SERIES_WEEKLY");
        params.put("symbol", symbol);

        try {
            Map<String, Object> responseBody = executeApiCall(params);
            Map<String, Map<String, String>> timeSeries =
                    (Map<String, Map<String, String>>) responseBody.get("Weekly Time Series");

            if (timeSeries == null || timeSeries.isEmpty()) {
                throw new ExternalApiException("Invalid response format from Alpha Vantage");
            }

            return parseWeeklyTimeSeriesData(symbol, timeSeries);
        } catch (Exception e) {
            throw handleApiException("Error fetching weekly prices", e);
        }
    }

    /**
     * Gets monthly price data using the TIME_SERIES_MONTHLY endpoint
     */
    public List<StockPriceDto> getMonthlyPrices(String symbol) {
        log.info("Fetching monthly prices for {}", symbol);

        Map<String, Object> params = new HashMap<>();
        params.put("function", "TIME_SERIES_MONTHLY");
        params.put("symbol", symbol);

        try {
            Map<String, Object> responseBody = executeApiCall(params);
            Map<String, Map<String, String>> timeSeries =
                    (Map<String, Map<String, String>>) responseBody.get("Monthly Time Series");

            if (timeSeries == null || timeSeries.isEmpty()) {
                throw new ExternalApiException("Invalid response format from Alpha Vantage");
            }

            return parseMonthlyTimeSeriesData(symbol, timeSeries);
        } catch (Exception e) {
            throw handleApiException("Error fetching monthly prices", e);
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
                // Stock market daily data is for market close (4:00 PM ET)
                LocalDateTime localDateTime = LocalDateTime.parse(dateStr + " 16:00:00", DATE_TIME_FORMATTER);

                // Create a zoned datetime in the market timezone
                ZonedDateTime marketTime = localDateTime.atZone(MARKET_TIMEZONE);

                StockPriceDto priceData = StockPriceDto.builder()
                        .symbol(symbol)
                        .timestamp(localDateTime)
                        .zonedTimestamp(marketTime)
                        .sourceTimezone(MARKET_TIMEZONE)
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
                LocalDateTime localDateTime = LocalDateTime.parse(dateTimeStr, DATE_TIME_FORMATTER);

                // Create a zoned datetime in the market timezone
                ZonedDateTime marketTime = localDateTime.atZone(MARKET_TIMEZONE);

                StockPriceDto priceData = StockPriceDto.builder()
                        .symbol(symbol)
                        .timestamp(localDateTime)
                        .zonedTimestamp(marketTime)
                        .sourceTimezone(MARKET_TIMEZONE)
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
     * Parses time series data from the weekly endpoint
     */
    public List<StockPriceDto> parseWeeklyTimeSeriesData(String symbol,
                                                         Map<String, Map<String, String>> timeSeries) {
        List<StockPriceDto> weeklyPrices = new ArrayList<>();

        // Parse each week's data
        for(Map.Entry<String, Map<String, String>> entry : timeSeries.entrySet()) {
            String dateStr = entry.getKey();
            Map<String, String> weeklyData = entry.getValue();

            try {
                // Weekly data is for the last trading day of the week (typically Friday at market close)
                LocalDateTime localDateTime = LocalDateTime.parse(dateStr + " 16:00:00", DATE_TIME_FORMATTER);

                // Create a zoned datetime in the market timezone
                ZonedDateTime marketTime = localDateTime.atZone(MARKET_TIMEZONE);

                StockPriceDto priceData = StockPriceDto.builder()
                        .symbol(symbol)
                        .timestamp(localDateTime)
                        .zonedTimestamp(marketTime)
                        .sourceTimezone(MARKET_TIMEZONE)
                        .open(parseBigDecimal(weeklyData.get("1. open")))
                        .high(parseBigDecimal(weeklyData.get("2. high")))
                        .low(parseBigDecimal(weeklyData.get("3. low")))
                        .price(parseBigDecimal(weeklyData.get("4. close"))) // Close price as the main price
                        .volume(parseLong(weeklyData.get("5. volume")))
                        .build();

                weeklyPrices.add(priceData);
            } catch (DateTimeParseException e) {
                log.warn("Failed to parse date: {}", dateStr, e);
            } catch (Exception e) {
                log.warn("Failed to parse date {} for: {}", dateStr, e.getMessage());
            }
        }

        // Sort by timestamp, newest first
        weeklyPrices.sort(Comparator.comparing(StockPriceDto::getTimestamp).reversed());

        return weeklyPrices;
    }

    /**
     * Parses time series data for monthly endpoint
     */
    private List<StockPriceDto> parseMonthlyTimeSeriesData(String symbol,
                                                           Map<String, Map<String, String>> timeSeries) {
        List<StockPriceDto> monthlyPrices = new ArrayList<>();

        // Parse each month's data
        for(Map.Entry<String, Map<String, String>> entry : timeSeries.entrySet()) {
            String dateStr = entry.getKey();
            Map<String, String> monthlyData = entry.getValue();
            try {
                // Monthly data is for the last trading day of the month at market close
                LocalDateTime localDateTime = LocalDateTime.parse(dateStr + " 16:00:00", DATE_TIME_FORMATTER);

                // Create a zoned datetime in the market timezone
                ZonedDateTime marketTime = localDateTime.atZone(MARKET_TIMEZONE);

                StockPriceDto priceData = StockPriceDto.builder()
                        .symbol(symbol)
                        .timestamp(localDateTime)
                        .zonedTimestamp(marketTime)
                        .sourceTimezone(MARKET_TIMEZONE)
                        .open(parseBigDecimal(monthlyData.get("1. open")))
                        .high(parseBigDecimal(monthlyData.get("2. high")))
                        .low(parseBigDecimal(monthlyData.get("3. low")))
                        .price(parseBigDecimal(monthlyData.get("4. close"))) // Close price as the main price
                        .volume(parseLong(monthlyData.get("5. volume")))
                        .build();

                monthlyPrices.add(priceData);
            } catch (DateTimeParseException e) {
                log.warn("Failed to parse date: {}", dateStr, e);
            } catch (Exception e) {
                log.warn("Failed to parse data for date {}: {}", dateStr, e.getMessage());
            }
        }

        // Sort by timestamp, newest first
        monthlyPrices.sort(Comparator.comparing(StockPriceDto::getTimestamp).reversed());

        return monthlyPrices;
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

    /**
     * Determines if the US stock market is currently open.
     * NYSE/NASDAQ trading hours are 9:30 AM - 4:00 PM Eastern Time, Monday-Friday.
     */
    private boolean isDuringMarketHours() {
        ZonedDateTime nyTime = ZonedDateTime.now(MARKET_TIMEZONE);

        // Check if it's a weekday
        int dayOfWeek = nyTime.getDayOfWeek().getValue();
        if (dayOfWeek > 5) { // Saturday = 6, Sunday = 7
            return false;
        }

        // Check if it's between 9:30 AM and 4:00 PM ET
        int hour = nyTime.getHour();
        int minute = nyTime.getMinute();

        // Before 9:30 AM
        if (hour < 9 || (hour == 9 && minute < 30)) {
            return false;
        }

        // After 4:00 PM
        return hour < 16;

        // Between 9:30 AM and 4:00 PM on a weekday
    }

    /**
     * Gets the latest daily price using the GLOBAL_QUOTE endpoint.
     */
    private StockPriceDto getLatestDailyPrice(String symbol) {
        Map<String, Object> params = new HashMap<>();
        params.put("function", "GLOBAL_QUOTE");
        params.put("symbol", symbol);

        Map<String, Object> responseBody = executeApiCall(params);
        Map<String, String> globalQuote = (Map<String, String>) responseBody.get("Global Quote");

        if (globalQuote == null || globalQuote.isEmpty()) {
            throw new ExternalApiException("Invalid response format from Alpha Vantage");
        }

        // Create a ZonedDateTime with the market timezone
        ZonedDateTime marketTime = ZonedDateTime.now(MARKET_TIMEZONE);

        return StockPriceDto.builder()
                .symbol(symbol)
                .price(parseBigDecimal(globalQuote.get("05. price")))
                .open(parseBigDecimal(globalQuote.get("02. open")))
                .high(parseBigDecimal(globalQuote.get("03. high")))
                .low(parseBigDecimal(globalQuote.get("04. low")))
                .volume(parseLong(globalQuote.get("06. volume")))
                .change(parseBigDecimal(globalQuote.get("09. change")))
                .changePercent(parseBigDecimal(globalQuote.get("10. change percent").replace("%", "")))
                .timestamp(marketTime.toLocalDateTime())
                .zonedTimestamp(marketTime)
                .sourceTimezone(MARKET_TIMEZONE)
                .build();
    }

    /**
     * Gets the most recent intraday price using the TIME_SERIES_INTRADAY endpoint
     * and calculates change values based on previous close.
     */
    private StockPriceDto getMostRecentIntradayPrice(String symbol) {
        try {
            // First, get the previous day's closing price for reference
            BigDecimal previousClose = null;
            try {
                // Use GLOBAL_QUOTE to get the previous close
                Map<String, Object> quoteParams = new HashMap<>();
                quoteParams.put("function", "GLOBAL_QUOTE");
                quoteParams.put("symbol", symbol);

                Map<String, Object> quoteResponse = executeApiCall(quoteParams);
                Map<String, String> globalQuote = (Map<String, String>) quoteResponse.get("Global Quote");

                if (globalQuote != null && !globalQuote.isEmpty()) {
                    // "08. previous close" contains the previous day's closing price
                    previousClose = parseBigDecimal(globalQuote.get("08. previous close"));
                    log.debug("Previous close for {}: {}", symbol, previousClose);
                }
            } catch (Exception e) {
                log.warn("Could not retrieve previous close for {}: {}", symbol, e.getMessage());
                // Continue even without previous close - we'll use a fallback
            }

            // Now get the current intraday price
            Map<String, Object> params = new HashMap<>();
            params.put("function", "TIME_SERIES_INTRADAY");
            params.put("symbol", symbol);
            params.put("interval", "1min");
            params.put("outputsize", "compact");

            Map<String, Object> responseBody = executeApiCall(params);

            // Extract metadata to verify the data is fresh
            Map<String, String> metadata = (Map<String, String>) responseBody.get("Meta Data");
            String lastRefreshed = metadata.get("3. Last Refreshed");

            // Extract the time series data
            String timeSeriesKey = "Time Series (1min)";
            Map<String, Map<String, String>> timeSeries = (Map<String, Map<String, String>>) responseBody.get(timeSeriesKey);

            if (timeSeries == null || timeSeries.isEmpty()) {
                throw new ExternalApiException("No intraday data available from Alpha Vantage");
            }

            // Get the most recent data point (first entry)
            Map.Entry<String, Map<String, String>> firstEntry = timeSeries.entrySet().iterator().next();
            String dateTimeStr = firstEntry.getKey();
            Map<String, String> priceData = firstEntry.getValue();

            // Parse the timestamp
            LocalDateTime timestamp;
            ZonedDateTime zonedTimestamp;
            try {
                timestamp = LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                zonedTimestamp = timestamp.atZone(MARKET_TIMEZONE);
            } catch (Exception e) {
                log.warn("Failed to parse intraday timestamp: {}", dateTimeStr);
                // Fallback to current time if parsing fails
                zonedTimestamp = ZonedDateTime.now(MARKET_TIMEZONE);
                timestamp = zonedTimestamp.toLocalDateTime();
            }

            // Parse the current price
            BigDecimal currentPrice = parseBigDecimal(priceData.get("4. close"));

            // Calculate change and percent change if we have a previous close
            BigDecimal change = null;
            BigDecimal changePercent = null;

            if (previousClose != null && previousClose.compareTo(BigDecimal.ZERO) > 0) {
                change = currentPrice.subtract(previousClose);
                changePercent = change.multiply(new BigDecimal("100"))
                        .divide(previousClose, 4, RoundingMode.HALF_UP);
                log.debug("Calculated change for {}: {} ({}%)", symbol, change, changePercent);
            } else {
                // If we couldn't get previous close, try to get day's open price as fallback
                BigDecimal todayOpen = parseBigDecimal(priceData.get("1. open"));
                if (todayOpen.compareTo(BigDecimal.ZERO) > 0) {
                    change = currentPrice.subtract(todayOpen);
                    changePercent = change.multiply(new BigDecimal("100"))
                            .divide(todayOpen, 4, RoundingMode.HALF_UP);
                    log.debug("Using day's open as fallback for change calculation: {} ({}%)", change, changePercent);
                } else {
                    log.warn("Could not calculate change values for {}", symbol);
                }
            }

            // Build the stock price DTO with change values
            return StockPriceDto.builder()
                    .symbol(symbol)
                    .price(currentPrice)
                    .open(parseBigDecimal(priceData.get("1. open")))
                    .high(parseBigDecimal(priceData.get("2. high")))
                    .low(parseBigDecimal(priceData.get("3. low")))
                    .volume(parseLong(priceData.get("5. volume")))
                    .change(change)
                    .changePercent(changePercent)
                    .timestamp(timestamp)
                    .zonedTimestamp(zonedTimestamp)
                    .sourceTimezone(MARKET_TIMEZONE)
                    .build();
        } catch (Exception e) {
            log.error("Error fetching intraday price for {}: {}", symbol, e.getMessage());
            throw new ExternalApiException("Failed to get intraday price: " + e.getMessage(), e);
        }
    }
}