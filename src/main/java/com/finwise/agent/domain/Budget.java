package com.finwise.agent.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity @Table(name = "budgets")
@Getter @Setter
public class Budget {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spending_category_id", nullable = false)
    private SpendingCategory spendingCategory;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyLimit;

    private Boolean rolloverEnabled = false;

    @Column(precision = 10, scale = 2)
    private BigDecimal rolloverAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;  // null = currently active

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}