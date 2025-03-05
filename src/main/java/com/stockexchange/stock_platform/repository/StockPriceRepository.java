package com.stockexchange.stock_platform.repository;

import com.stockexchange.stock_platform.model.entity.StockPrice;
import com.stockexchange.stock_platform.model.entity.StockPriceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockPriceRepository extends JpaRepository<StockPrice, StockPriceId> {

    // Find the most recent price for a symbol
    @Query("SELECT sp FROM StockPrice sp WHERE sp.symbol = :symbol ORDER BY sp.time DESC LIMIT 1")
    Optional<StockPrice> findLatestBySymbol(@Param("symbol") String symbol);

    // Find all prices for a symbol in a given time range
    List<StockPrice> findBySymbolAndTimeBetweenOrderByTimeAsc(
            String symbol, LocalDateTime startTime, LocalDateTime endTime);

    // Find the daily opening price for a symbol on a specific date
    @Query("SELECT sp FROM StockPrice sp WHERE sp.symbol = :symbol AND CAST(sp.time AS date) = CAST(:date AS date) ORDER BY sp.time ASC LIMIT 1")
    Optional<StockPrice> findOpeningPriceBySymbolAndDate(
            @Param("symbol") String symbol, @Param("date") LocalDateTime date);

    // Find the daily closing price for a symbol on a specific date
    @Query("SELECT sp FROM StockPrice sp WHERE sp.symbol = :symbol AND CAST(sp.time AS date) = CAST(:date AS date) ORDER BY sp.time DESC LIMIT 1")
    Optional<StockPrice> findClosingPriceBySymbolAndDate(
            @Param("symbol") String symbol, @Param("date") LocalDateTime date);

    // Find the highest price for a symbol in a given time range
    @Query("SELECT sp FROM StockPrice sp WHERE sp.symbol = :symbol AND sp.time BETWEEN :startTime AND :endTime ORDER BY sp.price DESC LIMIT 1")
    Optional<StockPrice> findHighestPriceBySymbolAndTimeRange(
            @Param("symbol") String symbol,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // Find the lowest price for a symbol in a given time range
    @Query("SELECT sp FROM StockPrice sp WHERE sp.symbol = :symbol AND sp.time BETWEEN :startTime AND :endTime ORDER BY sp.price ASC LIMIT 1")
    Optional<StockPrice> findLowestPriceBySymbolAndTimeRange(
            @Param("symbol") String symbol,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // Delete old price data (for maintenance)
    void deleteByTimeBefore(LocalDateTime cutoffTime);
}