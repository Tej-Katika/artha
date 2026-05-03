package com.artha.investments.ontology;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "securities")
@Getter @Setter
public class Security {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 16)
    private String ticker;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 16)
    private String assetClass;     // EQUITY | ETF | BOND | CRYPTO | COMMODITY

    @Column(length = 80)
    private String sector;

    @Column(length = 8)
    private String marketCapBucket; // LARGE | MID | SMALL | NA

    private Instant createdAt;

    @PrePersist
    void prePersist() { if (createdAt == null) createdAt = Instant.now(); }
}
