package com.artha.investments.ontology;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only trade record. Corrections write a reversing trade
 * rather than UPDATEing this row, so the audit trail is permanent.
 * No @PreUpdate hook by design.
 */
@Entity @Table(name = "trades")
@Getter @Setter
public class Trade {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "security_id", nullable = false)
    private Security security;

    @Column(nullable = false, length = 4)
    private String side;                 // BUY | SELL

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal fees = BigDecimal.ZERO;

    @Column(nullable = false)
    private Instant executedAt;

    @Column(columnDefinition = "text")
    private String provenanceJson;
}
