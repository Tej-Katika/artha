package com.artha.investments.ontology;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity @Table(name = "benchmarks")
@Getter @Setter
public class Benchmark {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, unique = true, length = 16)
    private String ticker;

    @Column(nullable = false, length = 16)
    private String category;       // BROAD | SECTOR | CRYPTO | BOND
}
