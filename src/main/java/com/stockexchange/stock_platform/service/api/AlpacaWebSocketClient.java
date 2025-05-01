package com.stockexchange.stock_platform.service.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockexchange.stock_platform.config.AlpacaConfig;
import com.stockexchange.stock_platform.dto.StockPriceDto;
import com.stockexchange.stock_platform.pattern.observer.StockPriceObserver;
import com.stockexchange.stock_platform.pattern.observer.StockPriceSubject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket client for real-time market data from Alpaca.
 * Specifically focused on receiving minute bar data for stock prices.
 */
@Service
@Slf4j
public class AlpacaWebSocketClient implements StockPriceSubject {

    private final AlpacaConfig config;
    private final ObjectMapper objectMapper;
    private final List<StockPriceObserver> observers = new ArrayList<>();
    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isAuthenticated = new AtomicBoolean(false);

    // Constants for WebSocket
    private static final String STOCKS_STREAM_PATH = "/v2/iex";
    private static final ZoneId MARKET_TIMEZONE = ZoneId.of("America/New_York");
    private static final ZoneId UTC_TIMEZONE = ZoneId.of("UTC");
    private final WebSocketClient client;
    private Mono<Void> webSocketConnection;
    private WebSocketSession webSocketSession;

    public AlpacaWebSocketClient(AlpacaConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.client = new ReactorNettyWebSocketClient();
    }

    @PostConstruct
    public void init() {
        log.info("Initializing Alpaca WebSocket client");
        connect();
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down Alpaca WebSocket client");
        disconnect();
    }

    /**
     * Establish a WebSocket connection to Alpaca's streaming API
     */
    public void connect() {
        // If already connected, don't try again
        if (isConnected.get()) {
            log.debug("WebSocket already connected");
            return;
        }

        log.info("Connecting to Alpaca WebSocket");

        // Build the WebSocket URL from the configuration
        String wsUrl = config.getWsBaseUrl() + STOCKS_STREAM_PATH;
        log.info("Connecting to WebSocket at {}", wsUrl);

        webSocketConnection = client.execute(
                        URI.create(wsUrl),
                        session -> {
                            // Handle connection established
                            this.webSocketSession = session;
                            isConnected.set(true);
                            log.info("Connected to Alpaca WebSocket");

                            // Authenticate immediately after connection
                            Mono<Void> authMono = authenticate(session);

                            // Handle incoming messages
                            Mono<Void> receiveMono = session.receive()
                                    .doOnNext(message -> processMessage(message.getPayloadAsText()))
                                    .doOnError(error -> {
                                        log.error("Error in WebSocket receive stream: {}", error.getMessage());
                                        isConnected.set(false);
                                        isAuthenticated.set(false);
                                    })
                                    .then();

                            // Combine the authentication and message handling
                            return authMono.then(receiveMono);
                        }
                )
                .doOnError(error -> {
                    log.error("Error establishing WebSocket connection: {}", error.getMessage());
                    isConnected.set(false);
                    isAuthenticated.set(false);
                });

        // Subscribe to the connection to initiate it
        webSocketConnection.subscribe(
                null,
                error -> log.error("WebSocket connection error: {}", error.getMessage()),
                () -> log.info("WebSocket connection completed")
        );
    }

    /**
     * Authenticate with the Alpaca WebSocket API using API keys
     */
    private Mono<Void> authenticate(WebSocketSession session) {
        try {
            // Create authentication message
            ObjectNode authMsg = objectMapper.createObjectNode();
            authMsg.put("action", "auth");
            authMsg.put("key", config.getApiKey());
            authMsg.put("secret", config.getApiSecret());

            String authJson = objectMapper.writeValueAsString(authMsg);
            log.debug("Sending authentication message");

            return session.send(Mono.just(session.textMessage(authJson)))
                    .doOnSuccess(v -> log.debug("Authentication message sent"));
        } catch (JsonProcessingException e) {
            log.error("Failed to create authentication message: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    /**
     * Process incoming WebSocket messages
     */
    private void processMessage(String message) {
        try {
            log.debug("Received WebSocket message: {}", message);

            // Parse the message
            JsonNode node = objectMapper.readTree(message);

            // Handle array of messages
            if (node.isArray()) {
                for (JsonNode msgNode : node) {
                    processSingleMessage(msgNode);
                }
            } else {
                // Single message
                processSingleMessage(node);
            }
        } catch (Exception e) {
            log.error("Error processing WebSocket message: {}", e.getMessage());
        }
    }

    /**
     * Process a single message from the WebSocket
     */
    private void processSingleMessage(JsonNode msgNode) {
        // Check for message type
        if (!msgNode.has("T")) {
            return;
        }

        String messageType = msgNode.get("T").asText();

        switch (messageType) {
            case "success":
                handleSuccessMessage(msgNode);
                break;
            case "error":
                handleErrorMessage(msgNode);
                break;
            case "subscription":
                handleSubscriptionMessage(msgNode);
                break;
            case "b":
                handleBarMessage(msgNode);
                break;
        }
    }

    /**
     * Handle success messages (connection, authentication)
     */
    private void handleSuccessMessage(JsonNode msgNode) {
        String msg = msgNode.has("msg") ? msgNode.get("msg").asText() : "";

        log.info("Received success message: {}", msg);

        if ("connected".equals(msg)) {
            // Connection successful
            isConnected.set(true);
        } else if ("authenticated".equals(msg)) {
            // Authentication successful
            isAuthenticated.set(true);
            // After authentication, resubscribe to symbols
            resubscribeSymbols();
        }
    }

    /**
     * Handle error messages
     */
    private void handleErrorMessage(JsonNode msgNode) {
        int code = msgNode.has("code") ? msgNode.get("code").asInt() : 0;
        String msg = msgNode.has("msg") ? msgNode.get("msg").asText() : "Unknown error";

        log.error("Received error message: Code {}, Message: {}", code, msg);

        // Handle authentication errors
        if (code == 402 || code == 403 || code == 404) {
            isAuthenticated.set(false);
        }

        // Connection issues may require reconnection
        if (code == 406 || code == 407) {
            disconnect();
            connect();
        }
    }

    /**
     * Handle subscription confirmation messages
     */
    private void handleSubscriptionMessage(JsonNode msgNode) {
        log.info("Subscription update received");

        if (msgNode.has("bars") && msgNode.get("bars").isArray()) {
            ArrayNode bars = (ArrayNode) msgNode.get("bars");
            log.info("Subscribed to {} bar streams", bars.size());
        }
    }

    /**
     * Handle bar messages containing aggregated price data
     */
    private void handleBarMessage(JsonNode msgNode) {
        try {
            // Only process if we have observers
            if (observers.isEmpty()) {
                return;
            }

            // Extract symbol
            String symbol = msgNode.get("S").asText().toUpperCase();

            // Extract OHLC data
            BigDecimal openPrice = new BigDecimal(msgNode.get("o").asText());
            BigDecimal highPrice = new BigDecimal(msgNode.get("h").asText());
            BigDecimal lowPrice = new BigDecimal(msgNode.get("l").asText());
            BigDecimal closePrice = new BigDecimal(msgNode.get("c").asText());
            int volume = msgNode.get("v").asInt();

            // Extract timestamp - Alpaca sends timestamps in UTC
            String timestampStr = msgNode.get("t").asText();
            ZonedDateTime utcTimestamp = ZonedDateTime.parse(timestampStr);

            // Store the market timestamp for database storage (as UTC)
            LocalDateTime timestamp = utcTimestamp.toLocalDateTime();

            // Create StockPriceDto with UTC timezone info
            // The StockPriceServiceImpl will be responsible for converting to the user's timezone
            StockPriceDto priceDto = StockPriceDto.builder()
                    .symbol(symbol)
                    .price(closePrice)
                    .open(openPrice)
                    .high(highPrice)
                    .low(lowPrice)
                    .volume((long) volume)
                    .timestamp(timestamp)
                    .zonedTimestamp(utcTimestamp)  // Store original UTC time from Alpaca
                    .sourceTimezone(UTC_TIMEZONE)  // Source timezone is UTC
                    .build();

            // Notify observers - they'll convert to user timezone as needed
            notifyObservers(priceDto);
        } catch (Exception e) {
            log.error("Error processing bar message: {}", e.getMessage());
        }
    }

    /**
     * Subscribe to real-time bar data for a single symbol.
     * Only actually sends one subscribe message the very first time.
     */
    public void subscribeToSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        symbol = symbol.toUpperCase();

        // only send subscribe once per symbol
        boolean newlyAdded = subscribedSymbols.add(symbol);
        if (!newlyAdded) {
            // already subscribed → nothing to do
            return;
        }

        log.info("Subscribing to bar data for symbol: {}", symbol);

        // if we’re already connected & authenticated, send the one subscribe frame now
        if (isConnected.get() && isAuthenticated.get()) {
            sendSubscriptionMessage(Collections.singletonList(symbol));
        }
    }

    /**
     * Subscribe to real-time bar data for multiple symbols in bulk.
     * Only sends subscribe frames for newly added symbols.
     */
    public void subscribeToSymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return;
        }

        List<String> toActuallySubscribe = new ArrayList<>();
        for (String s : symbols) {
            if (s != null && !s.isBlank() && subscribedSymbols.add(s.toUpperCase())) {
                toActuallySubscribe.add(s.toUpperCase());
            }
        }

        if (toActuallySubscribe.isEmpty()) {
            // nothing new to subscribe
            return;
        }

        log.info("Subscribing to bar data for {} new symbols", toActuallySubscribe.size());

        if (isConnected.get() && isAuthenticated.get()) {
            sendSubscriptionMessage(toActuallySubscribe);
        }
    }

    /**
     * Resubscribe to all currently tracked symbols after reconnection
     */
    private void resubscribeSymbols() {
        if (!subscribedSymbols.isEmpty()) {
            log.info("Resubscribing to {} symbols", subscribedSymbols.size());
            sendSubscriptionMessage(new ArrayList<>(subscribedSymbols));
        }
    }

    /**
     * Send a subscription message to the WebSocket for the specified symbols
     */
    private void sendSubscriptionMessage(List<String> symbols) {
        if (!isConnected.get() || symbols.isEmpty()) {
            return;
        }

        try {
            // Create subscription message
            ObjectNode subscribeMsg = objectMapper.createObjectNode();
            subscribeMsg.put("action", "subscribe");

            // Only subscribe to bar data (minute bars)
            ArrayNode barsArray = subscribeMsg.putArray("bars");

            // Add symbols to the bars array
            for (String symbol : symbols) {
                barsArray.add(symbol);
            }

            // Convert to JSON and send
            String subscribeJson = objectMapper.writeValueAsString(subscribeMsg);
            log.debug("Sending subscription message: {}", subscribeJson);

            if (webSocketSession != null && webSocketSession.isOpen()) {
                webSocketSession.send(Mono.just(webSocketSession.textMessage(subscribeJson)))
                        .subscribe(
                                null,
                                error -> log.error("Error sending subscription: {}", error.getMessage()),
                                () -> log.debug("Subscription message sent successfully")
                        );
            } else {
                log.warn("Cannot send subscription - no active session or session disposed");
            }
        } catch (Exception e) {
            log.error("Failed to subscribe to symbols: {}", e.getMessage());
        }
    }

    /**
     * Disconnect from the WebSocket
     */
    public void disconnect() {
        log.info("Disconnecting from Alpaca WebSocket");
        isConnected.set(false);
        isAuthenticated.set(false);
    }

    /**
     * Register an observer to receive price updates
     */
    @Override
    public void registerObserver(StockPriceObserver observer) {
        if (!observers.contains(observer)) {
            observers.add(observer);
        }
    }

    /**
     * Remove an observer
     */
    @Override
    public void removeObserver(StockPriceObserver observer) {
        observers.remove(observer);
    }

    /**
     * Notify all observers of a price update
     */
    @Override
    public void notifyObservers(StockPriceDto stockPrice) {
        for (StockPriceObserver observer : observers) {
            observer.update(stockPrice);
        }
    }

    /**
     * Check if the client is connected and authenticated
     */
    public boolean isReady() {
        return isConnected.get() && isAuthenticated.get();
    }

    /**
     * Get the current set of subscribed symbols
     */
    public Set<String> getSubscribedSymbols() {
        return new HashSet<>(subscribedSymbols);
    }
}
