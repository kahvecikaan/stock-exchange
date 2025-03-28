package com.stockexchange.stock_platform.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for handling timezone conversions throughout the application
 */
@Service
@Slf4j
public class TimezoneService {

    // Common stock market timezones
    public static final ZoneId NYSE_TIMEZONE = ZoneId.of("America/New_York");
    public static final ZoneId LSE_TIMEZONE = ZoneId.of("Europe/London");
    public static final ZoneId TOKYO_TIMEZONE = ZoneId.of("Asia/Tokyo");
    public static final ZoneId ISTANBUL_TIMEZONE = ZoneId.of("Europe/Istanbul");
    public static final ZoneId UTC = ZoneId.of("UTC");

    // Default timezone to use if none specified (most stock data is in NYSE timezone)
    public static final ZoneId DEFAULT_MARKET_TIMEZONE = NYSE_TIMEZONE;

    // Cache of market timezones by exchange code
    private final Map<String, ZoneId> exchangeTimezones = new ConcurrentHashMap<>();

    public TimezoneService() {
        // Initialize common exchange timezones
        exchangeTimezones.put("NYSE", NYSE_TIMEZONE);
        exchangeTimezones.put("NASDAQ", NYSE_TIMEZONE);
        exchangeTimezones.put("LSE", LSE_TIMEZONE);
        exchangeTimezones.put("TSE", TOKYO_TIMEZONE);
    }

    /**
     * Get the timezone for a specific exchange
     * @param exchangeCode The exchange code (NYSE, NASDAQ, etc.)
     * @return The timezone for the exchange
     */
    public ZoneId getExchangeTimezone(String exchangeCode) {
        return exchangeTimezones.getOrDefault(exchangeCode, DEFAULT_MARKET_TIMEZONE);
    }

    /**
     * Parse a timezone string into a ZoneId
     * @param timezoneStr Timezone string (e.g., "America/New_York", "Europe/Istanbul", "UTC")
     * @return The ZoneId or the default market timezone if invalid
     */
    public ZoneId parseTimezone(String timezoneStr) {
        if (timezoneStr == null || timezoneStr.isBlank()) {
            return DEFAULT_MARKET_TIMEZONE;
        }

        try {
            return ZoneId.of(timezoneStr);
        } catch (Exception e) {
            log.warn("Invalid timezone: {}, defaulting to {}", timezoneStr, DEFAULT_MARKET_TIMEZONE);
            return DEFAULT_MARKET_TIMEZONE;
        }
    }

    /**
     * Converts a UTC LocalDateTime from database to a ZonedDateTime in the specified timezone
     * @param utcDateTime LocalDateTime from database (assumed to be UTC)
     * @param targetTimezone Timezone to convert to
     * @return ZonedDateTime in the target timezone
     */
    public ZonedDateTime fromUtcToZoned(LocalDateTime utcDateTime, ZoneId targetTimezone) {
        if (utcDateTime == null) return null;
        return utcDateTime.atZone(UTC).withZoneSameInstant(targetTimezone);
    }

    /**
     * Converts a ZonedDateTime to a UTC LocalDateTime for database storage
     * @param zonedDateTime The ZonedDateTime to convert
     * @return LocalDateTime in UTC
     */
    public LocalDateTime toUtcLocalDateTime(ZonedDateTime zonedDateTime) {
        if (zonedDateTime == null) return null;
        return zonedDateTime.withZoneSameInstant(UTC).toLocalDateTime();
    }

    /**
     * Convert a ZonedDateTime to a different timezone
     * @param dateTime The ZonedDateTime to convert
     * @param targetZone The target timezone
     * @return The ZonedDateTime in the target timezone
     */
    public ZonedDateTime convertTimezone(ZonedDateTime dateTime, ZoneId targetZone) {
        if (dateTime == null) return null;
        return dateTime.withZoneSameInstant(targetZone);
    }

    /**
     * Format a ZonedDateTime with timezone information
     * @param dateTime The ZonedDateTime to format
     * @param format The format pattern
     * @return Formatted date/time string
     */
    public String formatWithTimezone(ZonedDateTime dateTime, String format) {
        if (dateTime == null) return null;
        return dateTime.format(DateTimeFormatter.ofPattern(format));
    }

    /**
     * Format a ZonedDateTime with both time and timezone name
     * @param dateTime The ZonedDateTime to format
     * @return Formatted string like "2025-03-26 15:30 EDT"
     */
    public String formatWithTimezoneName(ZonedDateTime dateTime) {
        if (dateTime == null) return null;
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
    }

    /**
     * Get a list of common timezone IDs for UI display
     * @return List of timezone strings
     */
    public List<String> getCommonTimezones() {
        return List.of(
                "UTC",
                "America/New_York",    // NYSE, NASDAQ
                "America/Chicago",     // CME
                "America/Los_Angeles", // US West Coast
                "Europe/London",       // LSE
                "Europe/Paris",        // Euronext Paris
                "Europe/Istanbul",     // Turkish timezone
                "Asia/Tokyo",          // TSE
                "Asia/Shanghai",       // SSE
                "Asia/Hong_Kong",      // HKEX
                "Asia/Singapore",      // SGX
                "Australia/Sydney"     // ASX
        );
    }

    /**
     * Get all available timezone IDs on the system
     * @return List of all timezone strings
     */
    public List<String> getAllTimezones() {
        return ZoneId.getAvailableZoneIds().stream()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Determine if a given time is during market hours for NYSE/NASDAQ
     * (9:30 AM - 4:00 PM Eastern Time, Monday-Friday)
     * @param timestamp The timestamp to check
     * @return true if during market hours
     */
    public boolean isDuringMarketHours(ZonedDateTime timestamp) {
        if (timestamp == null) return false;

        // Convert to NYSE timezone
        ZonedDateTime nyseTime = timestamp.withZoneSameInstant(NYSE_TIMEZONE);

        // Check if weekend
        DayOfWeek day = nyseTime.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }

        // Check time of day (9:30 AM - 4:00 PM)
        LocalTime time = nyseTime.toLocalTime();
        LocalTime marketOpen = LocalTime.of(9, 30);
        LocalTime marketClose = LocalTime.of(16, 0);

        return !time.isBefore(marketOpen) && !time.isAfter(marketClose);
    }

    /**
     * Get the current time in a specific timezone
     * @param timezone The timezone to get current time in
     * @return Current time in the specified timezone
     */
    public ZonedDateTime getCurrentTimeInZone(ZoneId timezone) {
        return ZonedDateTime.now(timezone);
    }
}