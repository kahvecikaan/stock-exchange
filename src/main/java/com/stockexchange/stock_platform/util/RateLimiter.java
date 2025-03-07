package com.stockexchange.stock_platform.util;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Simple token bucket rate limiter for API calls
 */
@Slf4j
public class RateLimiter {
    private final int maxRequestsPerMinute;
    private final Queue<LocalDateTime> requestTimestamps = new LinkedList<>();
    private final ReentrantLock lock = new ReentrantLock();

    public RateLimiter(int maxRequestsPerMinute) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }

    /**
     * Acquires a permit to make an API request, waiting if necessary to respect the rate limit
     * @return true if a permit was acquired, false if interrupted while waiting
     */
    public boolean acquire() {
        try {
            lock.lock();

            // Clean up old timestamps
            LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
            while (!requestTimestamps.isEmpty() && requestTimestamps.peek().isBefore(oneMinuteAgo)) {
                requestTimestamps.poll();
            }

            // If we've reached the limit, wait until we can proceed
            while (requestTimestamps.size() >= maxRequestsPerMinute) {
                lock.unlock();
                log.info("Rate limit reached, waiting before making next API call...");
                try {
                    // Wait for oldest request to expire
                    LocalDateTime oldestRequest = requestTimestamps.peek();
                    assert oldestRequest != null;
                    long waitTimeMs = ChronoUnit.MILLIS.between(
                            LocalDateTime.now(),
                            oldestRequest.plusMinutes(1)
                    );

                    if (waitTimeMs > 0) {
                        Thread.sleep(waitTimeMs + 100); // Add a small buffer
                    }
                } catch (InterruptedException e) {
                    log.warn("Interrupted while waiting for rate limit", e);
                    return false;
                }
                lock.lock();

                // Clean up again after waiting
                oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
                while (!requestTimestamps.isEmpty() && requestTimestamps.peek().isBefore(oneMinuteAgo)) {
                    requestTimestamps.poll();
                }
            }

            // Record this request
            requestTimestamps.add(LocalDateTime.now());
            return true;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}