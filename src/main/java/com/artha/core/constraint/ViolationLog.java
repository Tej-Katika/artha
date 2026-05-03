package com.artha.core.constraint;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only telemetry of every Constraint violation observed at
 * runtime. Fed by ConstraintChecker; used downstream to compute
 * catch-rate and false-positive-rate metrics for the constraint axis.
 *
 * Schema: src/main/resources/db/migration/V2__violation_log.sql
 */
@Entity
@Table(name = "violation_log")
@Getter @Setter
public class ViolationLog {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 80)
    private String constraintName;

    @Column(nullable = false, length = 20)
    private String domain;          // "banking" | "investments"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ConstraintGrade grade;

    private UUID userId;

    @Column(length = 80)
    private String sessionId;

    @Column(columnDefinition = "text")
    private String message;

    @Column(columnDefinition = "text")
    private String repairHint;

    /**
     * Was the agent able to produce a revised response that no longer
     * violated this constraint? Null until the revision loop completes.
     */
    private Boolean repaired;

    @Column(nullable = false)
    private Instant observedAt;

    @PrePersist
    void prePersist() {
        if (observedAt == null) observedAt = Instant.now();
    }
}
