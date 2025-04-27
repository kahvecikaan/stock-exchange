package com.stockexchange.stock_platform.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockexchange.stock_platform.config.AlpacaConfig;
import com.stockexchange.stock_platform.dto.SearchResultDto;
import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.exception.ExternalApiException;
import com.stockexchange.stock_platform.util.RateLimiter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
public class AlpacaClient {
    private final RestTemplate restTemplate;
    @Getter
    private final AlpacaConfig config;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 2000;
    private static final ZoneId MARKET_TIMEZONE = ZoneId.of("America/New_York");
    private static final ZoneId UTC_TIMEZONE = ZoneId.of("UTC");

    // Different feed options for different endpoints
    private static final List<String> LATEST_BAR_FEEDS = Arrays.asList("sip", "iex", "delayed_sip");
    private static final List<String> HISTORICAL_BAR_FEEDS = Arrays.asList("sip", "iex");

    public AlpacaClient(
            RestTemplate restTemplate,
            AlpacaConfig config,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.objectMapper = objectMapper;
        this.rateLimiter = new RateLimiter(config.getMaxRequestPerMinute());
        log.info("AlpacaClient initialized with max {} requests per minute", config.getMaxRequestPerMinute());
    }

    /**
     * Gets the current stock price using Alpaca's bar API
     */
    public StockPriceDto getCurrentPrice(String symbol) {
        log.info("Fetching current price for {} from Alpaca", symbol);

        // Try feeds in order of preference
        Exception lastException = null;

        for (String feed : LATEST_BAR_FEEDS) {
            try {
                return fetchCurrentPriceWithFeed(symbol, feed);
            } catch (Exception e) {
                log.warn("Failed to fetch current price with '{}' feed: {}", feed, e.getMessage());
                lastException = e;
                // Continue to next feed
            }
        }

        // If all feeds failed, throw the last exception
        log.error("All data feeds failed for current price of {}", symbol);
        throw handleApiException("Error fetching stock price", lastException != null ? lastException :
                new ExternalApiException("All data feeds failed"));
    }

    /**
     * Helper method to fetch current price with a specific feed
     */
    private StockPriceDto fetchCurrentPriceWithFeed(String symbol, String feed) {
        String url = config.getDataBaseUrl() + "/v2/stocks/" + symbol + "/bars/latest";

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url)
                .queryParam("feed", feed);

        // Execute API request and get response
        ResponseEntity<String> response = executeApiRequest(builder);

        try {
            // Parse the response to JSON
            JsonNode rootNode = objectMapper.readTree(response.getBody());

            // Check if bar data exists
            if (!rootNode.has("bar") || rootNode.get("bar").isNull()) {
                throw new ExternalApiException("No bar data available from Alpaca");
            }

            JsonNode barNode = rootNode.get("bar");

            // Extract data from JSON
            String timeStr = barNode.get("t").asText();
            ZonedDateTime timestamp = ZonedDateTime.parse(timeStr); // UTC timestamp

            // Parse price data
            BigDecimal currentPrice = new BigDecimal(barNode.get("c").asText());
            BigDecimal openPrice = new BigDecimal(barNode.get("o").asText());
            BigDecimal highPrice = new BigDecimal(barNode.get("h").asText());
            BigDecimal lowPrice = new BigDecimal(barNode.get("l").asText());
            Long volume = barNode.get("v").asLong();

            // Calculate change and percent change
            BigDecimal change = currentPrice.subtract(openPrice);
            BigDecimal changePercent = BigDecimal.ZERO;
            if (openPrice.compareTo(BigDecimal.ZERO) > 0) {
                changePercent = change.multiply(new BigDecimal("100"))
                        .divide(openPrice, 4, RoundingMode.HALF_UP);
            }

            log.info("Successfully fetched current price for {} using '{}' feed", symbol, feed);

            // Build the StockPriceDto
            return StockPriceDto.builder()
                    .symbol(symbol.toUpperCase())
                    .price(currentPrice)
                    .open(openPrice)
                    .high(highPrice)
                    .low(lowPrice)
                    .volume(volume)
                    .change(change)
                    .changePercent(changePercent)
                    .timestamp(timestamp.toLocalDateTime())
                    .zonedTimestamp(timestamp)
                    .sourceTimezone(UTC_TIMEZONE)  // Keep original UTC timezone
                    .build();
        } catch (Exception e) {
            log.error("Error processing response for {}: {}", symbol, e.getMessage());
            throw handleApiException("Error processing current price data", e);
        }
    }

    /**
     * Gets historical price data with flexible timeframe options
     * Tries multiple data feeds in order of preference
     */
    public List<StockPriceDto> getHistoricalBars(String symbol, String timeframe,
                                                 LocalDateTime startTime, LocalDateTime endTime) {
        // Calculate and log time period details
        long daysBetween = java.time.Duration.between(startTime, endTime).toDays();
        String periodType = daysBetween > 300 ? "1-year" : daysBetween > 60 ? "3-month" : "1-month";
        log.info("Fetching {} historical chart ({} bars) for {} - period: {} days",
                periodType, timeframe, symbol, daysBetween);

        try {
            String url = config.getDataBaseUrl() + "/v2/stocks/" + symbol + "/bars";

            // Convert local datetimes to RFC-3339 formatted strings in UTC
            ZonedDateTime utcStartTime = startTime.atZone(ZoneId.systemDefault())
                    .withZoneSameInstant(ZoneId.of("UTC"));
            ZonedDateTime utcEndTime = endTime.atZone(ZoneId.systemDefault())
                    .withZoneSameInstant(ZoneId.of("UTC"));

            // Store the exact formatted strings to reuse in pagination
            String startTimeStr = utcStartTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String endTimeStr = utcEndTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

            log.debug("Date range: from {} to {} UTC", startTimeStr, endTimeStr);

            // Try different data feeds in order of preference
            Exception lastException = null;

            for (String feed : HISTORICAL_BAR_FEEDS) {
                try {
                    log.debug("Attempting with feed: {} for timeframe: {}", feed, timeframe);

                    UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url)
                            .queryParam("timeframe", timeframe)
                            .queryParam("start", startTimeStr)
                            .queryParam("end", endTimeStr)
                            .queryParam("limit", 10000) // Maximum to ensure we get enough data
                            .queryParam("adjustment", "all")
                            .queryParam("feed", feed)
                            .queryParam("sort", "asc");

                    String fullUrl = builder.build().toUriString();
                    log.debug("Full request URL: {}", fullUrl);

                    // Execute API request with this feed
                    ResponseEntity<String> response = executeApiRequest(builder);
                    log.debug("Received response status: {}", response.getStatusCode());

                    // Process the response
                    JsonNode rootNode = objectMapper.readTree(response.getBody());
                    List<StockPriceDto> stockPrices = processBarData(rootNode, symbol);
                    log.debug("Initial response contained {} data points", stockPrices.size());

                    // Handle pagination if needed
                    if (rootNode.has("next_page_token") && !rootNode.get("next_page_token").isNull()) {
                        String nextPageToken = rootNode.get("next_page_token").asText();
                        log.debug("Pagination required - next_page_token: {}", nextPageToken);

                        try {
                            // IMPORTANT: Pass the exact same string formatting of dates used in original request
                            List<StockPriceDto> nextPagePrices = getNextBarPage(
                                    symbol, nextPageToken, timeframe, feed,
                                    startTimeStr, endTimeStr);

                            log.debug("Successfully retrieved {} additional data points from pagination",
                                    nextPagePrices.size());
                            stockPrices.addAll(nextPagePrices);
                        } catch (Exception e) {
                            log.error("Pagination failed: {} - Will continue with {} data points",
                                    e.getMessage(), stockPrices.size());
                        }
                    } else {
                        log.debug("No pagination required - all data retrieved in single request");
                    }

                    // Calculate changes after all data is collected
                    calculateChanges(stockPrices);

                    log.info("Successfully fetched {} bars for {} using '{}' feed",
                            stockPrices.size(), symbol, feed);
                    return stockPrices;
                }
                catch (Exception e) {
                    log.warn("Failed to fetch data with '{}' feed: {}", feed, e.getMessage());
                    lastException = e;
                    // Continue to next feed
                }
            }

            // If we've tried all feeds and none worked
            log.error("All data feeds failed for historical data of {}", symbol);
            throw new ExternalApiException("Could not fetch historical data with any available feed",
                    lastException != null ? lastException : new Exception("All feeds failed"));
        }
        catch (Exception e) {
            log.error("Error fetching bars for {}: {}", symbol, e.getMessage());
            throw handleApiException("Error fetching historical bars", e);
        }
    }

    /**
     * Helper method to fetch the next page of bars using a pagination token
     * Uses exact same parameter strings to maintain token validity
     */
    private List<StockPriceDto> getNextBarPage(String symbol, String nextPageToken,
                                               String timeframe, String feed,
                                               String startTimeStr, String endTimeStr) {
        try {
            log.debug("Fetching next page of {} bars for {} with token {} using {} feed",
                    timeframe, symbol, nextPageToken, feed);

            String url = config.getDataBaseUrl() + "/v2/stocks/" + symbol + "/bars";

            // CRITICAL: Use the exact same parameters from the original request
            // Don't recalculate or reformat dates - use the passed-in strings
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url)
                    .queryParam("timeframe", timeframe)
                    .queryParam("start", startTimeStr)  // Exact same string as original request
                    .queryParam("end", endTimeStr)      // Exact same string as original request
                    .queryParam("limit", 10000)
                    .queryParam("adjustment", "all")
                    .queryParam("feed", feed)
                    .queryParam("sort", "asc");

            // Build base URL as string
            String baseUrl = builder.toUriString();

            // Manually append the token to avoid URL encoding the Base64 padding characters
            String fullUrl = baseUrl + "&page_token=" + nextPageToken;

            log.debug("Pagination URL: {}", fullUrl);

            // Execute API request
            ResponseEntity<String> response = executeApiRequest(fullUrl);
            log.debug("Pagination response status: {}", response.getStatusCode());

            // Parse response and process bar data
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            List<StockPriceDto> prices = processBarData(rootNode, symbol);
            log.debug("Retrieved {} data points from this pagination page", prices.size());

            // Recursively get next page if token is present
            if (rootNode.has("next_page_token") && !rootNode.get("next_page_token").isNull()
                    && !rootNode.get("next_page_token").asText().equals("null")) {

                String token = rootNode.get("next_page_token").asText();
                log.debug("New pagination token: {}", token);

                // Prevent infinite loop
                if (token.equals(nextPageToken)) {
                    log.warn("Same token returned - stopping pagination to prevent infinite loop");
                    return prices;
                }

                try {
                    // Continue pagination with the new token but SAME original parameters
                    List<StockPriceDto> nextPrices = getNextBarPage(symbol, token,
                            timeframe, feed, startTimeStr, endTimeStr);
                    prices.addAll(nextPrices);
                } catch (Exception e) {
                    log.error("Error fetching subsequent pagination pages: {}", e.getMessage());
                }
            }

            return prices;
        } catch (Exception e) {
            log.error("Pagination request failed: {} - Symbol: {} - Token: {}",
                    e.getMessage(), symbol, nextPageToken);
            throw handleApiException("Error fetching next page", e);
        }
    }

    /**
     * Process bar data from a JSON response with additional logging
     */
    private List<StockPriceDto> processBarData(JsonNode rootNode, String symbol) {
        List<StockPriceDto> prices = new ArrayList<>();

        // Check if bars array exists
        if (!rootNode.has("bars")) {
            log.warn("Response missing 'bars' array for {}", symbol);
            return prices;  // Return empty list if no bars
        }

        if (!rootNode.get("bars").isArray()) {
            log.warn("'bars' is not an array in response for {}", symbol);
            return prices;  // Return empty list if not an array
        }

        JsonNode barsNode = rootNode.get("bars");
        int barCount = barsNode.size();
        log.debug("Response contains {} bars for {}", barCount, symbol);

        if (barCount == 0) {
            log.warn("Empty bars array in response for {}", symbol);
            return prices;
        }

        // Process each bar
        for (JsonNode barNode : barsNode) {
            try {
                StockPriceDto priceDto = parseBarNode(barNode, symbol);
                prices.add(priceDto);
            } catch (Exception e) {
                log.warn("Failed to parse bar data: {}", e.getMessage());
            }
        }

        log.debug("Successfully processed {} out of {} bars", prices.size(), barCount);
        return prices;
    }

    /**
     * Search for assets by symbol or name
     * @param query The search query (symbol or partial name)
     * @return List of matching assets
     */
    public List<SearchResultDto> searchAssets(String query) {
        log.info("Searching for assets matching: {}", query);

        try {
            // First try direct symbol lookup
            try {
                SearchResultDto exactMatch = getAssetBySymbol(query);
                if (exactMatch != null) {
                    return Collections.singletonList(exactMatch);
                }
            } catch (Exception e) {
                log.debug("No exact match found for symbol {}", query);
            }

            // If direct lookup fails, try asset list search
            String url = config.getTradingBaseUrl() + "/v2/assets";

            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url)
                    .queryParam("status", "active")
                    .queryParam("exchange", "");  // Leave empty to get all exchanges

            ResponseEntity<String> response = executeApiRequest(builder);
            JsonNode assetsNode = objectMapper.readTree(response.getBody());

            if (!assetsNode.isArray()) {
                throw new ExternalApiException("Invalid response format from Alpaca assets endpoint");
            }

            List<SearchResultDto> results = new ArrayList<>();
            String queryUpper = query.toUpperCase();

            // Process each asset
            for (JsonNode assetNode : assetsNode) {
                String symbol = assetNode.get("symbol").asText();
                String name = assetNode.has("name") ? assetNode.get("name").asText() : "";

                // Match by symbol or name containing the query
                if (symbol.toUpperCase().contains(queryUpper) ||
                        name.toUpperCase().contains(queryUpper)) {

                    SearchResultDto result = createSearchResultFromAsset(assetNode);
                    results.add(result);

                    // Limit to 10 results for performance
                    if (results.size() >= 10) {
                        break;
                    }
                }
            }

            return results;
        } catch (Exception e) {
            log.error("Error searching for assets: {}", e.getMessage());
            return Collections.emptyList(); // Return empty list on error rather than failing
        }
    }

    /**
     * Get asset data by exact symbol match
     * @param symbol The exact symbol to lookup
     * @return Asset data, or null if not found
     */
    public SearchResultDto getAssetBySymbol(String symbol) {
        log.info("Looking up asset with symbol: {}", symbol);

        try {
            String url = config.getTradingBaseUrl() + "/v2/assets/" + symbol.toUpperCase();

            ResponseEntity<String> response = executeApiRequest(builder(url));
            JsonNode assetNode = objectMapper.readTree(response.getBody());

            return createSearchResultFromAsset(assetNode);
        } catch (Exception e) {
            log.debug("Asset lookup failed for symbol {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Create a SearchResultDto from an asset JSON node
     */
    private SearchResultDto createSearchResultFromAsset(JsonNode assetNode) {
        return SearchResultDto.builder()
                .id(assetNode.has("id") ? assetNode.get("id").asText() : "")
                .symbol(assetNode.get("symbol").asText())
                .name(assetNode.has("name") ? assetNode.get("name").asText() : "")
                .type(assetNode.has("class") ? assetNode.get("class").asText() : "")
                .exchange(assetNode.has("exchange") ? assetNode.get("exchange").asText() : "")
                .region("US") // Alpaca primarily handles US stocks
                .currency("USD") // Alpaca primarily deals with USD
                .tradable(assetNode.has("tradable") && assetNode.get("tradable").asBoolean())
                .fractionable(assetNode.has("fractionable") && assetNode.get("fractionable").asBoolean())
                .build();
    }

    /**
     * Execute an API request with proper headers and rate limiting
     */
    private ResponseEntity<String> executeApiRequest(UriComponentsBuilder builder) {
        return executeApiRequest(builder.toUriString());
    }

    /**
     * Execute an API request with proper headers and rate limiting using direct URL
     */
    private ResponseEntity<String> executeApiRequest(String url) {
        HttpHeaders headers = createHeaders();
        HttpEntity<?> entity = new HttpEntity<>(headers);

        if (!rateLimiter.acquire()) {
            throw new ExternalApiException("Rate limiter interrupted");
        }

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new ExternalApiException("Failed to fetch data: " + response.getStatusCode());
        }

        return response;
    }

    /**
     * Create a basic UriComponentsBuilder for a URL
     */
    private UriComponentsBuilder builder(String url) {
        return UriComponentsBuilder.fromUriString(url);
    }

    /**
     * Parse a single bar node into a StockPriceDto
     */
    private StockPriceDto parseBarNode(JsonNode barNode, String symbol) {
        // Extract timestamp and convert to ZonedDateTime
        String timeStr = barNode.get("t").asText();
        ZonedDateTime timestamp = ZonedDateTime.parse(timeStr); // UTC timestamp

        // Extract price data
        BigDecimal openPrice = new BigDecimal(barNode.get("o").asText());
        BigDecimal highPrice = new BigDecimal(barNode.get("h").asText());
        BigDecimal lowPrice = new BigDecimal(barNode.get("l").asText());
        BigDecimal closePrice = new BigDecimal(barNode.get("c").asText());
        Long volume = barNode.get("v").asLong();

        // Create StockPriceDto - we store the original UTC
        // The timezone conversion happens at service level when delivering to users
        return StockPriceDto.builder()
                .symbol(symbol.toUpperCase())
                .price(closePrice)
                .open(openPrice)
                .high(highPrice)
                .low(lowPrice)
                .volume(volume)
                .timestamp(timestamp.toLocalDateTime())
                .zonedTimestamp(timestamp)
                .sourceTimezone(UTC_TIMEZONE)
                .build();
    }

    /**
     * Create API headers with authentication
     */
    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("APCA-API-KEY-ID", config.getApiKey());
        headers.set("APCA-API-SECRET-KEY", config.getApiSecret());
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        return headers;
    }

    /**
     * Handles exceptions from API calls
     */
    private ExternalApiException handleApiException(String message, Exception e) {
        if (e instanceof ExternalApiException) {
            return (ExternalApiException) e;
        }
        return new ExternalApiException(message + ": " + e.getMessage(), e);
    }

    /**
     * Calculate changes and percent changes relative to the first (earliest) price point
     * @param prices List of price data points
     */
    private void calculateChanges(List<StockPriceDto> prices) {
        if (prices == null || prices.isEmpty()) {
            return;
        }

        // Get reference price (first/the earliest price in the dataset)
        // Alpaca already returns data in ascending order, so we can just use the first element
        BigDecimal referencePrice = prices.getFirst().getPrice();

        // Calculate change and percent change for each price point
        for (StockPriceDto pricePoint : prices) {
            BigDecimal priceChange = pricePoint.getPrice().subtract(referencePrice);
            pricePoint.setChange(priceChange);

            if (referencePrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal changePercent = priceChange
                        .multiply(new BigDecimal("100"))
                        .divide(referencePrice, 4, RoundingMode.HALF_UP);
                pricePoint.setChangePercent(changePercent);
            } else {
                pricePoint.setChangePercent(BigDecimal.ZERO);
            }
        }

        log.debug("Calculated changes for {} data points relative to reference price {}",
                prices.size(), referencePrice);
    }
}