package com.artha.agent.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "spending_categories")
@Getter @Setter
public class SpendingCategory {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_type_id")
    private MerchantType merchantType;

    @Column(length = 7)
    private String colorHex;  // '#4CAF50'

    @Column(length = 50)
    private String icon;      // 'shopping-cart'

    private Boolean isSystem = true;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() { createdAt = Instant.now(); }
}