package com.artha.core.action;

/**
 * Thrown by {@link Action#postcondition(Object, Object)} when the
 * post-execution state does not satisfy the action's contract.
 *
 * Indicates a logic bug in the Action's execute() implementation —
 * the action claimed to perform a transformation but the resulting
 * state does not reflect it. ActionExecutor catches this, rolls back
 * the transaction, and writes a FAILURE_POSTCONDITION audit.
 */
public class PostconditionViolation extends RuntimeException {

    public PostconditionViolation(String message) {
        super(message);
    }

    public PostconditionViolation(String message, Throwable cause) {
        super(message, cause);
    }
}
