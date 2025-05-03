package com.stockexchange.stock_platform.service;

import com.stockexchange.stock_platform.dto.MarketCalendarDto;
import com.stockexchange.stock_platform.dto.MarketClockDto;

import java.time.ZonedDateTime;

public interface MarketCalendarService {
    boolean isMarketOpen();
    ZonedDateTime todayOpen();              // only valid if open()
    MarketCalendarDto lastTradingDay();
    MarketClockDto getMarketClock();
}
