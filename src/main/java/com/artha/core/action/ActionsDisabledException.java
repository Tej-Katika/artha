package com.artha.core.action;

/**
 * Thrown when an Action is invoked while the Actions axis is disabled
 * (ablation Condition B; {@code artha.actions.enabled=false}).
 *
 * Distinct from {@link PreconditionViolation} / {@link PostconditionViolation}
 * so that callers can distinguish "tried to run an action while the axis
 * was disabled" from "input failed the action's contract". Action-tool
 * adapters catch this and return a clean {@code ToolResult.error} to the
 * agent rather than a stack trace.
 */
public class ActionsDisabledException extends RuntimeException {
    public ActionsDisabledException(String message) { super(message); }
}
