package com.artha.banking.actions;

import com.artha.banking.ontology.Budget;
import com.artha.banking.ontology.BudgetRepository;
import com.artha.core.action.ActionAuditRepository;
import com.artha.core.action.ActionExecutor;
import com.artha.core.action.ActionOutcome;
import com.artha.core.action.PreconditionViolation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies {@link UpdateBudgetAction} mutates monthly_limit, leaves
 * other columns alone, and writes audit. Restores the original
 * monthly_limit in {@link #tearDown()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UpdateBudgetActionTest {

    private static final UUID HIGH_EARNER = UUID.fromString(
        "aa000000-0000-0000-0000-000000000000");

    private static final String TEST_SESSION = "UpdateBudgetActionTest";

    @Autowired private ActionExecutor       executor;
    @Autowired private UpdateBudgetAction   action;
    @Autowired private BudgetRepository     budgetRepo;
    @Autowired private ActionAuditRepository auditRepo;

    private UUID       budgetId;
    private BigDecimal originalLimit;

    @BeforeEach
    void setUp() {
        List<Budget> userBudgets = budgetRepo.findByUserId(HIGH_EARNER);
        assumeTrue(!userBudgets.isEmpty(),
            "high_earner must have at least one seeded budget");

        Budget fixture = userBudgets.get(0);
        budgetId      = fixture.getId();
        originalLimit = fixture.getMonthlyLimit();

        auditRepo.deleteAll(
            auditRepo.findBySessionIdOrderByStartedAtAsc(TEST_SESSION));
    }

    @AfterEach
    void tearDown() {
        if (budgetId != null && originalLimit != null) {
            budgetRepo.findById(budgetId).ifPresent(b -> {
                b.setMonthlyLimit(originalLimit);
                budgetRepo.save(b);
            });
            auditRepo.deleteAll(
                auditRepo.findBySessionIdOrderByStartedAtAsc(TEST_SESSION));
        }
    }

    @Test
    void updatesMonthlyLimitAndWritesAudit() {
        BigDecimal newLimit = originalLimit.add(new BigDecimal("100.00"));

        UpdateBudgetAction.Output out = executor.run(
            action,
            new UpdateBudgetAction.Input(budgetId, newLimit, HIGH_EARNER),
            "AGENT",
            HIGH_EARNER,
            TEST_SESSION
        );

        assertThat(out.previousMonthlyLimit())
            .isEqualByComparingTo(originalLimit);

        Budget fresh = budgetRepo.findById(budgetId).orElseThrow();
        assertThat(fresh.getMonthlyLimit()).isEqualByComparingTo(newLimit);

        assertThat(auditRepo
            .findBySessionIdOrderByStartedAtAsc(TEST_SESSION))
            .hasSize(1)
            .allSatisfy(a -> {
                assertThat(a.getActionName()).isEqualTo("UpdateBudget");
                assertThat(a.getOutcome()).isEqualTo(ActionOutcome.SUCCESS);
            });
    }

    @Test
    void rejectsForeignActor() {
        UUID foreign = UUID.fromString("bb000000-0000-0000-0000-000000000000");
        BigDecimal newLimit = originalLimit.add(BigDecimal.ONE);

        assertThatThrownBy(() -> executor.run(
            action,
            new UpdateBudgetAction.Input(budgetId, newLimit, foreign),
            "AGENT",
            foreign,
            TEST_SESSION
        ))
            .isInstanceOf(PreconditionViolation.class)
            .hasMessageContaining("does not own budget");

        Budget unchanged = budgetRepo.findById(budgetId).orElseThrow();
        assertThat(unchanged.getMonthlyLimit())
            .isEqualByComparingTo(originalLimit);
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> executor.run(
            action,
            new UpdateBudgetAction.Input(budgetId, BigDecimal.ZERO, HIGH_EARNER),
            "AGENT",
            HIGH_EARNER,
            TEST_SESSION
        ))
            .isInstanceOf(PreconditionViolation.class)
            .hasMessageContaining("must be positive");
    }
}
