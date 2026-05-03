package com.artha.investments.ontology;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "risk_profiles")
@Getter @Setter
public class RiskProfile {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", nullable = false, unique = true)
    private Portfolio portfolio;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal targetEquityPct;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal targetBondPct;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal targetAltPct = BigDecimal.ZERO;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal maxDrawdownTolerancePct;

    private Instant updatedAt;

    @PrePersist @PreUpdate
    void touch() { updatedAt = Instant.now(); }
}
