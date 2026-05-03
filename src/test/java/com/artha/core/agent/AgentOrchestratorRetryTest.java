package com.artha.core.agent;

import com.artha.core.constraint.ConstraintChecker;
import com.artha.core.constraint.ConstraintChecker.CheckResult;
import com.artha.core.constraint.ConstraintChecker.Violation;
import com.artha.core.constraint.ConstraintGrade;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link AgentOrchestrator#decideRetry} and the
 * repair-prompt builder. No Spring context, no Anthropic calls — these
 * exercise the retry decision tree and prompt formatting in isolation.
 *
 * End-to-end orchestrator coverage that requires hitting the
 * Anthropic API lives in the {@code *IT} smoke tests under
 * {@code src/test/java/com/artha/it/}, gated by ARTHA_LIVE_LLM.
 */
class AgentOrchestratorRetryTest {

    private static final int K = AgentOrchestrator.MAX_CONSTRAINT_RETRIES;

    @Test
    void noViolations_doesNotRetry() {
        CheckResult clean = new CheckResult(List.of(), 0);
        AgentOrchestrator.RetryDecision rd =
            AgentOrchestrator.decideRetry(clean, 0, K);
        assertThat(rd.retry()).isFalse();
        assertThat(rd.repairPrompt()).isNull();
    }

    @Test
    void hardViolation_retriesWhenBudgetAvailable() {
        CheckResult cr = new CheckResult(
            List.of(new Violation(
                "GoalProgressBound", ConstraintGrade.HARD,
                "Goal X exceeds target", "Abort and surface the issue.")),
            0);
        AgentOrchestrator.RetryDecision rd =
            AgentOrchestrator.decideRetry(cr, 0, K);
        assertThat(rd.retry()).isTrue();
        assertThat(rd.repairPrompt())
            .contains("GoalProgressBound")
            .contains("Goal X exceeds target")
            .contains("Abort and surface the issue.");
    }

    @Test
    void softViolation_retriesWhenBudgetAvailable() {
        CheckResult cr = new CheckResult(
            List.of(new Violation(
                "SpendingMagnitude", ConstraintGrade.SOFT,
                "Claimed spending is implausible",
                "Re-derive from get_spending_summary.")),
            1);
        AgentOrchestrator.RetryDecision rd =
            AgentOrchestrator.decideRetry(cr, 1, K);
        assertThat(rd.retry()).isTrue();
        assertThat(rd.repairPrompt())
            .contains("SpendingMagnitude")
            .contains("get_spending_summary");
    }

    @Test
    void budgetExhausted_doesNotRetry() {
        CheckResult cr = new CheckResult(
            List.of(new Violation(
                "AnomalyEvidence", ConstraintGrade.SOFT,
                "Count mismatch", "Re-run get_anomalies.")),
            0);
        AgentOrchestrator.RetryDecision rd =
            AgentOrchestrator.decideRetry(cr, K, K);
        assertThat(rd.retry()).isFalse();
        assertThat(rd.repairPrompt()).isNull();
    }

    @Test
    void advisoryOnly_doesNotRetry() {
        CheckResult cr = new CheckResult(
            List.of(new Violation(
                "StyleHint", ConstraintGrade.ADVISORY,
                "Tone could be warmer", null)),
            0);
        AgentOrchestrator.RetryDecision rd =
            AgentOrchestrator.decideRetry(cr, 0, K);
        assertThat(rd.retry()).isFalse();
    }

    @Test
    void mixedHardAndSoft_retriesAndPromptListsBoth() {
        CheckResult cr = new CheckResult(
            List.of(
                new Violation("GoalProgressBound", ConstraintGrade.HARD,
                    "Goal X negative", "Abort."),
                new Violation("SpendingMagnitude", ConstraintGrade.SOFT,
                    "Spending implausible", "Re-derive.")),
            0);
        AgentOrchestrator.RetryDecision rd =
            AgentOrchestrator.decideRetry(cr, 0, K);
        assertThat(rd.retry()).isTrue();
        assertThat(rd.repairPrompt())
            .contains("1. [GoalProgressBound]")
            .contains("2. [SpendingMagnitude]");
    }

    @Test
    void buildRepairPrompt_skipsBlankRepairHint() {
        String prompt = AgentOrchestrator.buildRepairPrompt(List.of(
            new Violation("X", ConstraintGrade.HARD, "msg", null),
            new Violation("Y", ConstraintGrade.HARD, "msg2", "")));
        assertThat(prompt)
            .doesNotContain("How to fix:");  // both hints empty
    }

    @Test
    void nullCheckResult_doesNotRetry() {
        AgentOrchestrator.RetryDecision rd =
            AgentOrchestrator.decideRetry(null, 0, K);
        assertThat(rd.retry()).isFalse();
    }
}
