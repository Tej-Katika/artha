package com.artha.core.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Runs an Action's P → E → Q sequence with transactional integrity
 * and append-only auditing.
 *
 * Invariants enforced here (not in individual Actions):
 *   1. Every invocation produces exactly one ActionAudit row.
 *   2. Failure of any of P, E, Q rolls the data transaction back.
 *      The audit row itself is written in a separate transaction
 *      (REQUIRES_NEW) so the audit survives the rollback.
 *   3. Audit row's outcome reflects which step failed (or SUCCESS).
 *
 * This is the single point where the §4.3 Hoare-triple soundness
 * property is enforced. Individual Actions trust the executor to
 * handle audit and transaction concerns.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionExecutor {

    private final ActionAuditRepository auditRepo;
    private final ObjectMapper          objectMapper;

    /**
     * Execute an Action and return its output.
     *
     * @param action  the Action to invoke
     * @param input   typed input
     * @param actor   "AGENT" | "USER" | "SYSTEM"
     * @param userId  optional UUID of the user the action operates on
     * @param sessionId optional agent session id
     * @param <I>     input type
     * @param <O>     output type
     * @return the action's output on SUCCESS
     * @throws PreconditionViolation if P(input) failed
     * @throws PostconditionViolation if Q(input, output) failed
     * @throws RuntimeException if E(input) threw — wrapped via cause
     */
    public <I, O> O run(Action<I, O> action,
                        I input,
                        String actor,
                        UUID userId,
                        String sessionId) {

        Instant startedAt = Instant.now();
        ActionAudit audit = new ActionAudit();
        audit.setActionName(action.name());
        audit.setDomain(action.domain());
        audit.setActor(actor);
        audit.setUserId(userId);
        audit.setSessionId(sessionId);
        audit.setInputJson(safeWriteJson(input));
        audit.setStartedAt(startedAt);

        try {
            action.precondition(input);
        } catch (PreconditionViolation pv) {
            finishAudit(audit, ActionOutcome.FAILURE_PRECONDITION, pv.getMessage(), null);
            throw pv;
        }

        O output;
        try {
            output = runInTransaction(action, input);
        } catch (PostconditionViolation pv) {
            finishAudit(audit, ActionOutcome.FAILURE_POSTCONDITION, pv.getMessage(), null);
            throw pv;
        } catch (RuntimeException ex) {
            finishAudit(audit, ActionOutcome.FAILURE_EXECUTION, ex.getMessage(), null);
            throw ex;
        }

        finishAudit(audit, ActionOutcome.SUCCESS, null, output);
        return output;
    }

    /**
     * E followed by Q, both inside the same data transaction. If Q
     * throws PostconditionViolation, the data transaction rolls back.
     * The audit write happens in finishAudit() which uses REQUIRES_NEW
     * to survive that rollback.
     */
    @Transactional(rollbackFor = Throwable.class)
    protected <I, O> O runInTransaction(Action<I, O> action, I input) {
        O output = action.execute(input);
        action.postcondition(input, output);
        return output;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void finishAudit(ActionAudit audit,
                               ActionOutcome outcome,
                               String errorMessage,
                               Object output) {
        audit.setOutcome(outcome);
        audit.setErrorMessage(errorMessage);
        audit.setOutputJson(safeWriteJson(output));
        audit.setEndedAt(Instant.now());
        try {
            auditRepo.save(audit);
        } catch (RuntimeException auditEx) {
            // Auditing must never mask the original exception.
            log.error("Failed to persist ActionAudit (action={}, outcome={}): {}",
                audit.getActionName(), outcome, auditEx.getMessage(), auditEx);
        }
    }

    private String safeWriteJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "<unserializable: " + ex.getMessage() + ">";
        }
    }
}
