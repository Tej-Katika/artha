package com.artha.core.action;

/**
 * Terminal outcome of an Action invocation.
 *
 * Used in the action_audit table's `outcome` column and surfaced in
 * ActionExecutor return values. SUCCESS is the only outcome where the
 * transaction commits; all FAILURE_* outcomes imply rollback.
 */
public enum ActionOutcome {
    SUCCESS,
    FAILURE_PRECONDITION,
    FAILURE_EXECUTION,
    FAILURE_POSTCONDITION,
    ROLLED_BACK
}
