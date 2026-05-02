package com.artha.banking.ontology;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "recurring_bills")
@Getter
@Setter
public class RecurringBill {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    private UUID merchantProfileId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal expectedAmount;

    @Column(precision = 10, scale = 2)
    private BigDecimal amountTolerance = BigDecimal.ONE;

    @Column(nullable = false, length = 20)
    private String billingCycle; // MONTHLY, ANNUAL, WEEKLY

    private LocalDate nextExpectedDate;

    private LocalDate lastSeenDate;

    @Column(length = 20)
    private String detectionSource = "AUTO"; // AUTO or MANUAL

    @Column(precision = 3, scale = 2)
    private BigDecimal confidenceScore;

    private Boolean isActive = true;

    private UUID spendingCategoryId;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}