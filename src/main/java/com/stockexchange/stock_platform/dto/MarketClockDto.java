package com.stockexchange.stock_platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.ZonedDateTime;

@Data
public class MarketClockDto {
    private ZonedDateTime timestamp;

    @JsonProperty("is_open")
    private boolean isOpen;

    @JsonProperty("next_open")
    private ZonedDateTime nextOpen;

    @JsonProperty("next_close")
    private ZonedDateTime nextClose;
}
