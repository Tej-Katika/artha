package com.artha.banking.actions;

import com.artha.banking.ontology.FinancialGoal;
import com.artha.banking.ontology.FinancialGoalRepository;
import com.artha.core.action.ActionAudit;
import com.artha.core.action.ActionAuditRepository;
import com.artha.core.action.ActionExecutor;
import com.artha.core.action.ActionOutcome;
import com.artha.core.action.PreconditionViolation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link CreateGoalAction} persists a new goal in the
 * canonical initial state and writes a matching audit row.
 *
 * Each test cleans up any goals it creates plus the audit rows it
 * generates so the eval dataset stays reproducible.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CreateGoalActionTest {

    private static final UUID HIGH_EARNER = UUID.fromString(
        "aa000000-0000-0000-0000-000000000000");

    private static final String TEST_SESSION = "CreateGoalActionTest";

    @Autowired private ActionExecutor          executor;
    @Autowired private CreateGoalAction        action;
    @Autowired private FinancialGoalRepository goalRepo;
    @Autowired private ActionAuditRepository   auditRepo;

    private final List<UUID> createdGoalIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (UUID id : createdGoalIds) goalRepo.deleteById(id);
        createdGoalIds.clear();
        auditRepo.deleteAll(
            auditRepo.findBySessionIdOrderByStartedAtAsc(TEST_SESSION));
    }

    @Test
    void createsActiveGoalAtZeroProgress() {
        long auditCountBefore = auditRepo.count();

        CreateGoalAction.Input in = new CreateGoalAction.Input(
            HIGH_EARNER,
            "Test Goal " + UUID.randomUUID(),
            "SAVINGS",
            new BigDecimal("5000.00"),
            LocalDate.now().plusYears(1),
            new BigDecimal("250.00"),
            "Created by integration test"
        );

        CreateGoalAction.Output out = executor.run(
            action, in, "AGENT", HIGH_EARNER, TEST_SESSION);

        assertThat(out.goalId()).isNotNull();
        createdGoalIds.add(out.goalId());

        FinancialGoal saved = goalRepo.findById(out.goalId()).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(HIGH_EARNER);
        assertThat(saved.getName()).isEqualTo(in.name());
        assertThat(saved.getGoalType()).isEqualTo("SAVINGS");
        assertThat(saved.getTargetAmount())
            .isEqualByComparingTo(in.targetAmount());
        assertThat(saved.getCurrentAmount()).isEqualByComparingTo("0.00");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getCreatedAt()).isNotNull();

        assertThat(auditRepo.count()).isEqualTo(auditCountBefore + 1);
        List<ActionAudit> audits =
            auditRepo.findBySessionIdOrderByStartedAtAsc(TEST_SESSION);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getActionName()).isEqualTo("CreateGoal");
        assertThat(audits.get(0).getOutcome()).isEqualTo(ActionOutcome.SUCCESS);
    }

    @Test
    void rejectsUnknownGoalType() {
        long auditCountBefore = auditRepo.count();

        CreateGoalAction.Input in = new CreateGoalAction.Input(
            HIGH_EARNER, "x", "VACATION", new BigDecimal("1.00"),
            null, null, null);

        assertThatThrownBy(() ->
            executor.run(action, in, "AGENT", HIGH_EARNER, TEST_SESSION))
            .isInstanceOf(PreconditionViolation.class)
            .hasMessageContaining("goalType must be one of");

        assertThat(auditRepo.count())
            .as("audit row written even on precondition failure")
            .isEqualTo(auditCountBefore + 1);
        assertThat(auditRepo
            .findBySessionIdOrderByStartedAtAsc(TEST_SESSION).get(0)
            .getOutcome()).isEqualTo(ActionOutcome.FAILURE_PRECONDITION);
    }

    @Test
    void rejectsNonPositiveTargetAmount() {
        long auditCountBefore = auditRepo.count();

        CreateGoalAction.Input in = new CreateGoalAction.Input(
            HIGH_EARNER, "x", "SAVINGS", BigDecimal.ZERO,
            null, null, null);

        assertThatThrownBy(() ->
            executor.run(action, in, "AGENT", HIGH_EARNER, TEST_SESSION))
            .isInstanceOf(PreconditionViolation.class)
            .hasMessageContaining("targetAmount must be positive");

        assertThat(auditRepo.count()).isEqualTo(auditCountBefore + 1);
    }
}
