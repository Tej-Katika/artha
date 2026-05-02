package com.artha.core.action;

/**
 * Thrown by {@link Action#precondition(Object)} when the action
 * cannot be safely executed. ActionExecutor catches this, writes a
 * FAILURE_PRECONDITION audit, and aborts without invoking execute().
 *
 * Unchecked because preconditions are part of the action contract —
 * callers should not be forced to handle them at every call site;
 * the executor handles them centrally.
 */
public class PreconditionViolation extends RuntimeException {

    public PreconditionViolation(String message) {
        super(message);
    }

    public PreconditionViolation(String message, Throwable cause) {
        super(message, cause);
    }
}
