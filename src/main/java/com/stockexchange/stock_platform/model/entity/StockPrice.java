package com.stockexchange.stock_platform.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_prices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(StockPriceId.class)
public class StockPrice {
    // Composite primary key for time-series data
    @Id
    @Column(name = "time", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime time;

    @Id
    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column
    private Long volume;

    @Column(precision = 19, scale = 4)
    private BigDecimal high;

    @Column(precision = 19, scale = 4)
    private BigDecimal low;

    @Column(precision = 19, scale = 4)
    private BigDecimal open;
}