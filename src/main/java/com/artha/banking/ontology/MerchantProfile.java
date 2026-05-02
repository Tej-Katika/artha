package com.artha.banking.ontology;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "merchant_profiles")
@Getter @Setter
public class MerchantProfile {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String canonicalName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_type_id")
    private MerchantType merchantType;

    @Column(nullable = false)
    private Boolean isRecurring = false;

    private BigDecimal typicalAmountMin;
    private BigDecimal typicalAmountMax;

    // Stored as PostgreSQL TEXT[] array
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] nameVariants;

    private String website;
    private String notes;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}