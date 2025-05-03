package com.stockexchange.stock_platform.service.impl;

import com.stockexchange.stock_platform.dto.MarketCalendarDto;
import com.stockexchange.stock_platform.dto.MarketClockDto;
import com.stockexchange.stock_platform.service.MarketCalendarService;
import com.stockexchange.stock_platform.service.api.AlpacaClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MarketCalendarServiceImpl implements MarketCalendarService {
    private final AlpacaClient alpacaClient;
    public static final ZoneId NY = ZoneId.of("America/New_York");

    @Override
    public boolean isMarketOpen() {
        MarketClockDto clock = alpacaClient.getMarketClock();
        return clock.isOpen();
    }

    @Override
    public ZonedDateTime todayOpen() {
        LocalDate today = LocalDate.now(NY);
        List<MarketCalendarDto> cal = alpacaClient.getMarketCalendar(today, today);
        if (cal.isEmpty()) {
            throw new IllegalStateException("No market calendar entry for today");
        }
        MarketCalendarDto entry = cal.getFirst();
        // combine date + open time in NY, return as ZonedDateTime
        return ZonedDateTime.of(entry.getDate(), entry.getOpen(), NY);
    }

    @Override
    public MarketCalendarDto lastTradingDay() {
        // look back up to 7 calendar days to find the last trading day
        LocalDate end = LocalDate.now(NY).minusDays(1);
        LocalDate start = end.minusDays(7);
        List<MarketCalendarDto> cal = alpacaClient.getMarketCalendar(start, end);
        if (cal.isEmpty()) {
            throw new IllegalStateException("No trading days found in last week");
        }
        // they come sorted ascending by date -> pick the last element
        return cal.getLast();
    }

    @Override
    public MarketClockDto getMarketClock() {
        return alpacaClient.getMarketClock();
    }
}
