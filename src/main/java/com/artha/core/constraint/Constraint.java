package com.artha.core.constraint;

import java.util.Set;

/**
 * A first-class predicate over the ontology + a set of claims drawn
 * from a candidate agent response.
 *
 * Defined in research/ONTOLOGY_V2_SPEC.md §6.2 as the 4-tuple
 * ⟨name, ϕ, grade, repair_hint⟩. Java realization splits ϕ into
 * the {@link #evaluate} method and exposes the metadata via the
 * remaining accessors.
 *
 * Implementations are Spring beans; ConstraintRegistry discovers
 * them at startup the same way ToolRegistry discovers tools.
 *
 * Constraints are stateless — all dependencies (repositories,
 * computed services) are injected.
 */
public interface Constraint {

    /** Identifier for telemetry / audit (e.g., "BudgetArithmetic"). */
    String name();

    /** Domain this constraint applies to: "banking" | "investments". */
    String domain();

    /** Failure semantics — see {@link ConstraintGrade}. */
    ConstraintGrade grade();

    /**
     * Natural-language template fed back to the agent on SOFT
     * violations. May reference placeholders the constraint fills in
     * via the Violated.repairHint() at evaluation time.
     */
    String repairHintTemplate();

    /**
     * Evaluate the constraint over the current state plus the agent's
     * extracted factual claims.
     *
     * @return Satisfied | Violated | Indeterminate
     */
    ConstraintResult evaluate(EvaluationContext ctx, Set<FactualClaim> claims);
}
