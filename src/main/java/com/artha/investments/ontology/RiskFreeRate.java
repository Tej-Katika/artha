package com.artha.investments.ontology;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity @Table(name = "risk_free_rate")
@Getter @Setter
public class RiskFreeRate {

    @Id
    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(name = "dgs10_pct", nullable = false, precision = 7, scale = 4)
    private BigDecimal dgs10Pct;
}
