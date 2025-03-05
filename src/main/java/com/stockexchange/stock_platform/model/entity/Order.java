package com.stockexchange.stock_platform.model.entity;

import com.stockexchange.stock_platform.model.enums.OrderSide;
import com.stockexchange.stock_platform.model.enums.OrderStatus;
import com.stockexchange.stock_platform.model.enums.OrderType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 10)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderStatus status;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    @Column(precision = 19, scale = 4)
    private BigDecimal price;
}