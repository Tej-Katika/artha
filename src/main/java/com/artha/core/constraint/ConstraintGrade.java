package com.artha.core.constraint;

/**
 * Failure semantics for a Constraint, per
 * research/ONTOLOGY_V2_SPEC.md §6.3.
 *
 *   HARD     — logical/arithmetic invariant the system cannot violate;
 *              violation forces agent revision (blocking)
 *   SOFT     — likely-hallucination smell; injected as repair_hint
 *              into the agent context, up to K retries
 *   ADVISORY — style/quality signal; logged in audit, not blocking
 */
public enum ConstraintGrade {
    HARD,
    SOFT,
    ADVISORY
}
