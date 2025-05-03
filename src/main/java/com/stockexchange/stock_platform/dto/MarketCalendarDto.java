package com.stockexchange.stock_platform.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MarketCalendarDto {
    private LocalDate date;

    // parse "09:30" into a LocalTime
    @JsonFormat(pattern = "HH:mm")
    private LocalTime open;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime close;
}
