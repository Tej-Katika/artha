package com.finwise.agent.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "classification_rules")
@Getter @Setter
public class ClassificationRule {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String pattern;         // 'WHOLEFDS%', 'NETFLIX'

    @Column(length = 20)
    private String patternType = "PREFIX"; // PREFIX, EXACT, CONTAINS, REGEX

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_profile_id")
    private MerchantProfile merchantProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spending_category_id")
    private SpendingCategory spendingCategory;

    private Integer priority = 100; // lower = higher priority

    private Boolean isActive = true;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() { createdAt = Instant.now(); }
}