package com.artha.agent.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity @Table(name = "financial_goals")
@Getter @Setter
public class FinancialGoal {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 255)
    private String name;             // 'Emergency Fund'

    @Column(nullable = false, length = 50)
    private String goalType;         // SAVINGS, DEBT_PAYOFF, PURCHASE

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal targetAmount;

    @Column(precision = 12, scale = 2)
    private BigDecimal currentAmount = BigDecimal.ZERO;

    @Column(precision = 10, scale = 2)
    private BigDecimal monthlyContribution;

    private LocalDate targetDate;

    @Column(length = 20)
    private String status = "ACTIVE"; // ACTIVE, ACHIEVED, PAUSED, CANCELLED

    private Integer priority = 1;

    @Column(columnDefinition = "text")
    private String notes;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}