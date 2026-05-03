package com.artha.investments.ontology;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "lots")
@Getter @Setter
public class Lot {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id", nullable = false)
    private Position position;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal costBasis;

    @Column(nullable = false)
    private Instant acquiredAt;

    private Instant closedAt;

    @Column(nullable = false, length = 8)
    private String lotMethod = "FIFO";  // FIFO | LIFO | SPEC_ID
}
