package com.stockexchange.stock_platform.controller;

import com.stockexchange.stock_platform.dto.MarketClockDto;
import com.stockexchange.stock_platform.service.MarketCalendarService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketController {

    private final MarketCalendarService marketCalendarService;

    /**
     * Returns { isOpen, nextOpen, nextClose } as provided by Alpaca.
     */
    @GetMapping("/status")
    public MarketClockDto status() {
        return marketCalendarService.getMarketClock();
    }
}
