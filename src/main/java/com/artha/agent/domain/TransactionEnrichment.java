package com.artha.agent.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "transaction_enrichments")
@Getter @Setter
public class TransactionEnrichment {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // One-to-one with Transaction
    @Column(nullable = false, unique = true)
    private UUID transactionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_profile_id")
    private MerchantProfile merchantProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spending_category_id")
    private SpendingCategory spendingCategory;

    private UUID recurringBillId;

    @Column(length = 255)
    private String canonicalMerchantName;

    @Column(precision = 3, scale = 2)
    private BigDecimal enrichmentConfidence;  // 0.00 - 1.00

    @Column(length = 20)
    private String enrichmentSource = "RULES"; // RULES, AI, MANUAL

    private Boolean isAnomaly = false;

    @Column(columnDefinition = "text")
    private String anomalyReason;

    private UUID budgetId;

    @Column(precision = 5, scale = 2)
    private BigDecimal budgetUtilizationPct;  // 34.50 = 34.5%

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}