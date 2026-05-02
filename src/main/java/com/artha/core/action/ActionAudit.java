package com.artha.core.action;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit record for every Action invocation.
 *
 * One row per invocation, regardless of outcome. Inputs and outputs
 * are persisted as JSON strings so the schema is stable across action
 * types. Foreign-key relationships back to ontology objects are
 * handled at query time, not at the schema level — keeping this table
 * loosely coupled to domain schemas.
 *
 * Schema: see src/main/resources/db/migration/V2__action_audit.sql
 */
@Entity
@Table(name = "action_audit")
@Getter @Setter
public class ActionAudit {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 80)
    private String actionName;

    @Column(nullable = false, length = 20)
    private String domain;          // "banking" | "investments"

    /** "AGENT" | "USER" | "SYSTEM" — who initiated the action. */
    @Column(nullable = false, length = 20)
    private String actor;

    /** Optional: the user this action operated on behalf of. */
    private UUID userId;

    /** Optional: the agent session within which this action ran. */
    @Column(length = 80)
    private String sessionId;

    @Column(columnDefinition = "text")
    private String inputJson;

    @Column(columnDefinition = "text")
    private String outputJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ActionOutcome outcome;

    /** Populated on FAILURE_* outcomes; null on SUCCESS. */
    @Column(columnDefinition = "text")
    private String errorMessage;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant endedAt;

    @PrePersist
    void prePersist() {
        if (startedAt == null) startedAt = Instant.now();
        if (endedAt == null)   endedAt   = Instant.now();
    }
}
