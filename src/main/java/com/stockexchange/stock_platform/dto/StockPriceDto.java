package com.stockexchange.stock_platform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockPriceDto {
    private String symbol;
    private BigDecimal price;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private Long volume;
    private BigDecimal change;
    private BigDecimal changePercent;
    private LocalDateTime timestamp;

    // for timezone handling
    private ZonedDateTime zonedTimestamp;
    private ZoneId sourceTimezone;

    /**
     * Gets the timestamp in the specified timezone
     * @param targetTimezone The timezone to convert to
     * @return The timestamp in the requested timezone
     */
    public ZonedDateTime getTimestampInTimezone(ZoneId targetTimezone) {
        if (zonedTimestamp == null) {
            // Fall back to the timestamp field if zonedTimestamp isn't available
            if (timestamp != null && sourceTimezone != null) {
                return timestamp.atZone(sourceTimezone).withZoneSameInstant(targetTimezone);
            }
            return null;
        }

        return zonedTimestamp.withZoneSameInstant(targetTimezone);
    }

    /**
     * Helper method to get a formatted time string in the specified timezone
     * @param targetTimezone The timezone to convert to
     * @param format The format pattern to use
     * @return Formatted time string
     */
    public String getFormattedTimestamp(ZoneId targetTimezone, String format) {
        ZonedDateTime zdt = getTimestampInTimezone(targetTimezone);
        if (zdt == null) return null;
        return zdt.format(DateTimeFormatter.ofPattern(format));
    }
}
