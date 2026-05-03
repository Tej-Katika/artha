package com.artha.investments.ontology;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity @Table(name = "daily_prices")
@IdClass(DailyPriceId.class)
@Getter @Setter
public class DailyPrice {

    @Id
    @Column(name = "security_id", nullable = false)
    private UUID securityId;

    @Id
    @Column(name = "price_date", nullable = false)
    private LocalDate priceDate;

    @Column(name = "open_price", precision = 19, scale = 4)
    private BigDecimal openPrice;

    @Column(name = "high_price", precision = 19, scale = 4)
    private BigDecimal highPrice;

    @Column(name = "low_price", precision = 19, scale = 4)
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal closePrice;

    private Long volume;

    @Column(name = "adj_close", precision = 19, scale = 4)
    private BigDecimal adjClose;
}
