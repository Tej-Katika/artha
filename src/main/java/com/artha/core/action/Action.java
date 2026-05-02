package com.artha.core.action;

/**
 * A typed, audited transformation of the ontology state.
 *
 * Defined formally in research/ONTOLOGY_V2_SPEC.md §4.2 as the 5-tuple
 * ⟨τ_in, τ_out, P, E, Q⟩. Java realization:
 *
 *   τ_in  → the generic parameter I
 *   τ_out → the generic parameter O
 *   P     → precondition(input)
 *   E     → execute(input)
 *   Q     → postcondition(input, output)
 *
 * Read-only tools are the degenerate case (postcondition asserts no
 * state change). v2 only refactors write operations into Actions;
 * existing v1 read tools are unchanged.
 *
 * Implementations are Spring beans. The Action itself is stateless —
 * any persistence is performed via injected repositories. The
 * ActionExecutor wraps invocation with audit + transaction + rollback.
 *
 * Soundness guarantee enforced by ActionExecutor:
 *   ∀σ, x : P(σ, x) → Q(σ, x, E(σ, x).output, E(σ, x).state)
 *
 * @param <I> input record type
 * @param <O> output record type
 */
public interface Action<I, O> {

    /** Unique action name within a domain (e.g., "RecategorizeTransaction"). */
    String name();

    /** Domain this action belongs to: "banking" | "investments". */
    String domain();

    /**
     * Precondition predicate P. Implementations throw if the action
     * cannot be safely executed (referenced entity missing,
     * authorization fails, input invariant broken). On throw, the
     * ActionExecutor writes a FAILURE_PRECONDITION audit and aborts.
     *
     * Must not modify state. Should be cheap; called before execute.
     */
    void precondition(I input) throws PreconditionViolation;

    /**
     * Execute the transformation E. Called inside a Spring-managed
     * transaction by ActionExecutor. Throwing rolls the transaction
     * back and produces a FAILURE_EXECUTION audit.
     */
    O execute(I input);

    /**
     * Postcondition predicate Q. Verifies the new state satisfies the
     * action's contract. Throwing causes rollback and a
     * FAILURE_POSTCONDITION audit.
     *
     * Re-reads state via repositories rather than trusting `output` —
     * this is what makes the Hoare-triple soundness check meaningful.
     */
    void postcondition(I input, O output) throws PostconditionViolation;
}
