package com.artha.core.constraint;

/**
 * Outcome of evaluating a Constraint, per
 * research/ONTOLOGY_V2_SPEC.md §6.2:
 *
 *   ConstraintResult := Satisfied | Violated(message) | Indeterminate(reason)
 *
 * Sealed so the constraint checker's exhaustive switch can be
 * verified at compile time.
 */
public sealed interface ConstraintResult
    permits ConstraintResult.Satisfied,
            ConstraintResult.Violated,
            ConstraintResult.Indeterminate {

    /** Constraint passed. */
    record Satisfied() implements ConstraintResult {
        public static final Satisfied INSTANCE = new Satisfied();
    }

    /**
     * Constraint failed. `message` describes the violation;
     * `repairHint` is fed back to the agent on SOFT-grade violations.
     */
    record Violated(String message, String repairHint)
        implements ConstraintResult {
        public Violated(String message) { this(message, null); }
    }

    /**
     * Constraint could not be evaluated (e.g., required data missing).
     * Treated as non-blocking.
     */
    record Indeterminate(String reason) implements ConstraintResult { }
}
