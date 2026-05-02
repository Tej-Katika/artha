package com.artha.banking.constraints;

import com.artha.banking.ontology.FinancialGoal;
import com.artha.banking.ontology.FinancialGoalRepository;
import com.artha.banking.ontology.TransactionRepository;
import com.artha.banking.ontology.TransactionEnrichmentRepository;
import com.artha.core.constraint.ConstraintChecker;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for the first batch of banking constraints,
 * including end-to-end ConstraintChecker dispatch.
 *
 * Uses the eval seed data (high_earner archetype). Every test
 * cleans up any rows it creates in @AfterEach.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BankingConstraintsTest {

    private static final UUID HIGH_EARNER = UUID.fromString(
        "aa000000-0000-0000-0000-000000000000");

    @Autowired private ConstraintChecker             checker;
    @Autowired private GoalProgressBoundConstraint   goalConstraint;
    @Autowired private AnomalyEvidenceConstraint     anomalyConstraint;
    @Autowired private SpendingMagnitudeConstraint   spendingConstraint;

    @Autowired private FinancialGoalRepository       goalRepo;
    @Autowired private TransactionEnrichmentRepository enrichRepo;
    @Autowired private TransactionRepository         txRepo;

    private final List<UUID> createdGoalIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdGoalIds.forEach(goalRepo::deleteById);
        createdGoalIds.clear();
    }

    /**
     * Eval data is frozen at 2024-12-31; using Instant.now() would
     * give a 365-day window that contains zero transactions.
     */
    private static final Instant EVAL_REFERENCE_DATE =
        Instant.parse("2024-12-31T23:59:59Z");

    private EvaluationContext ctx() {
        return new EvaluationContext(HIGH_EARNER, EVAL_REFERENCE_DATE, "");
    }

    // ── GoalProgressBoundConstraint (HARD ontology) ─────────────

    @Test
    void goalProgressBoundSatisfiedOnSeedData() {
        ConstraintResult result =
            goalConstraint.evaluate(ctx(), Set.of());
        assertThat(result).isInstanceOf(ConstraintResult.Satisfied.class);
    }

    @Test
    void goalProgressBoundCatchesOverProgress() {
        FinancialGoal g = new FinancialGoal();
        g.setUserId(HIGH_EARNER);
        g.setName("CONSTRAINT_TEST_OVERSHOT");
        g.setGoalType("SAVINGS");
        g.setTargetAmount(new BigDecimal("100.00"));
        g.setCurrentAmount(new BigDecimal("150.00"));   // exceeds target
        g.setStatus("ACTIVE");
        FinancialGoal saved = goalRepo.save(g);
        createdGoalIds.add(saved.getId());

        ConstraintResult result =
            goalConstraint.evaluate(ctx(), Set.of());

        assertThat(result).isInstanceOf(ConstraintResult.Violated.class);
        ConstraintResult.Violated v = (ConstraintResult.Violated) result;
        assertThat(v.message()).contains("exceeds target");
    }

    // ── AnomalyEvidenceConstraint (SOFT claim-driven) ───────────

    @Test
    void anomalyEvidenceSatisfiedWithMatchingClaim() {
        BigDecimal actual = anomalyConstraint.currentCount(ctx());
        FactualClaim claim = new FactualClaim(
            "anomaly_count", "user", actual, java.util.Map.of(), 0, 0);

        assertThat(anomalyConstraint.evaluate(ctx(), Set.of(claim)))
            .isInstanceOf(ConstraintResult.Satisfied.class);
    }

    @Test
    void anomalyEvidenceCatchesInflatedClaim() {
        BigDecimal actual = anomalyConstraint.currentCount(ctx());
        BigDecimal inflated = actual.add(new BigDecimal("100"));
        FactualClaim claim = new FactualClaim(
            "anomaly_count", "user", inflated, java.util.Map.of(), 0, 0);

        ConstraintResult result =
            anomalyConstraint.evaluate(ctx(), Set.of(claim));
        assertThat(result).isInstanceOf(ConstraintResult.Violated.class);
        assertThat(((ConstraintResult.Violated) result).message())
            .contains("but the detector reports")
            .containsPattern("\\d+ anomalies");
    }

    @Test
    void anomalyEvidenceVacuouslyPassesWhenNoClaim() {
        assertThat(anomalyConstraint.evaluate(ctx(), Set.of()))
            .isInstanceOf(ConstraintResult.Satisfied.class);
    }

    // ── SpendingMagnitudeConstraint (SOFT claim-driven) ─────────

    @Test
    void spendingMagnitudeCatchesAbsurdClaim() {
        FactualClaim absurd = new FactualClaim(
            "spending_amount", "user",
            new BigDecimal("999999999.00"), java.util.Map.of(),
            0, 0);

        assumeTrue(txRepo.findByUserIdOrderByPostDateDesc(HIGH_EARNER).size() > 0,
            "Need transactions for high_earner");

        ConstraintResult result =
            spendingConstraint.evaluate(ctx(), Set.of(absurd));
        assertThat(result).isInstanceOf(ConstraintResult.Violated.class);
    }

    @Test
    void spendingMagnitudeAcceptsModestClaim() {
        FactualClaim modest = new FactualClaim(
            "spending_amount", "user",
            new BigDecimal("10.00"), java.util.Map.of(),
            0, 0);

        ConstraintResult result =
            spendingConstraint.evaluate(ctx(), Set.of(modest));
        assertThat(result).isInstanceOf(ConstraintResult.Satisfied.class);
    }

    // ── ConstraintChecker dispatch ──────────────────────────────

    @Test
    void checkerRunsAllRegisteredConstraints() {
        ConstraintChecker.CheckResult result = checker.check(
            "banking", HIGH_EARNER, EVAL_REFERENCE_DATE,
            "I noticed 999999 anomalies and you spent $999,999,999.00.");

        // Both AnomalyEvidence and SpendingMagnitude should fire
        assertThat(result.violations())
            .extracting(ConstraintChecker.Violation::constraintName)
            .contains("AnomalyEvidence", "SpendingMagnitude");
        assertThat(result.hasSoft()).isTrue();
        assertThat(result.satisfied()).isFalse();
    }

    @Test
    void checkerSatisfiedOnInnocuousResponse() {
        ConstraintChecker.CheckResult result = checker.check(
            "banking", HIGH_EARNER, EVAL_REFERENCE_DATE,
            "Here is a summary of your finances. Let me know if anything stands out.");

        assertThat(result.satisfied()).isTrue();
    }

    @Test
    void checkerHardSignalSurfacesGoalIntegrityIssue() {
        FinancialGoal g = new FinancialGoal();
        g.setUserId(HIGH_EARNER);
        g.setName("CONSTRAINT_TEST_NEGATIVE");
        g.setGoalType("SAVINGS");
        g.setTargetAmount(new BigDecimal("1000.00"));
        g.setCurrentAmount(new BigDecimal("-50.00"));   // negative → HARD violation
        g.setStatus("ACTIVE");
        createdGoalIds.add(goalRepo.save(g).getId());

        ConstraintChecker.CheckResult result = checker.check(
            "banking", HIGH_EARNER, EVAL_REFERENCE_DATE, "Your goal looks fine.");

        assertThat(result.hasHard()).isTrue();
        assertThat(result.violations())
            .extracting(ConstraintChecker.Violation::grade)
            .contains(ConstraintGrade.HARD);
    }
}
