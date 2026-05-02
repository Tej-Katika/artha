package com.artha.banking.actions;

import com.artha.banking.ontology.SpendingCategory;
import com.artha.banking.ontology.SpendingCategoryRepository;
import com.artha.banking.ontology.Transaction;
import com.artha.banking.ontology.TransactionEnrichment;
import com.artha.banking.ontology.TransactionEnrichmentRepository;
import com.artha.banking.ontology.TransactionRepository;
import com.artha.core.action.ActionAudit;
import com.artha.core.action.ActionAuditRepository;
import com.artha.core.action.ActionExecutor;
import com.artha.core.action.ActionOutcome;
import com.artha.core.action.PreconditionViolation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test for the Action axis going live in Week 3.
 *
 * Drives RecategorizeTransactionAction through ActionExecutor and
 * verifies all three contractual properties:
 *   1. Hoare-triple soundness — postcondition holds after execute
 *   2. Audit completeness   — exactly one ActionAudit row written
 *   3. Persistence isolation — exactly one enrichment row touched
 *
 * Operates on real eval data (high_earner archetype, UUID
 * aa00...). Captures the original enrichment state in @BeforeEach
 * and restores it in @AfterEach so the eval dataset stays
 * reproducible.
 *
 * Requires PostgreSQL on localhost:5432 with the action_audit table
 * created (V2__action_audit.sql applied).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RecategorizeTransactionActionTest {

    /** high_earner archetype seed user (per reference_eval_artifacts memory). */
    private static final UUID HIGH_EARNER = UUID.fromString(
        "aa000000-0000-0000-0000-000000000000");

    private static final String TEST_SESSION = "RecategorizeTransactionActionTest";

    @Autowired private ActionExecutor                executor;
    @Autowired private RecategorizeTransactionAction action;
    @Autowired private TransactionRepository           txRepo;
    @Autowired private TransactionEnrichmentRepository enrichRepo;
    @Autowired private SpendingCategoryRepository      categoryRepo;
    @Autowired private ActionAuditRepository           auditRepo;

    private UUID    testTransactionId;
    private UUID    alternativeCategoryId;
    private boolean enrichmentExistedBefore;
    private UUID    originalCategoryId;
    private String  originalSource;

    @BeforeEach
    void setUp() {
        List<Transaction> txns = txRepo.findByUserIdOrderByPostDateDesc(HIGH_EARNER);
        assumeTrue(!txns.isEmpty(),
            "Eval seed data must include high_earner transactions");
        testTransactionId = txns.get(0).getId();

        Optional<TransactionEnrichment> existing =
            enrichRepo.findByTransactionId(testTransactionId);
        existing.ifPresentOrElse(e -> {
            enrichmentExistedBefore = true;
            originalCategoryId = e.getSpendingCategory() != null
                ? e.getSpendingCategory().getId() : null;
            originalSource = e.getEnrichmentSource();
        }, () -> enrichmentExistedBefore = false);

        List<SpendingCategory> userCategories =
            categoryRepo.findByUserId(HIGH_EARNER);
        assumeTrue(userCategories.size() >= 2,
            "Need at least 2 categories for high_earner to test recategorization");

        alternativeCategoryId = userCategories.stream()
            .map(SpendingCategory::getId)
            .filter(id -> !id.equals(originalCategoryId))
            .findFirst()
            .orElseThrow();

        // Clear any audit rows lingering from a prior test run
        auditRepo.deleteAll(
            auditRepo.findBySessionIdOrderByStartedAtAsc(TEST_SESSION));
    }

    @AfterEach
    void tearDown() {
        if (testTransactionId == null) return;

        Optional<TransactionEnrichment> current =
            enrichRepo.findByTransactionId(testTransactionId);

        if (enrichmentExistedBefore) {
            current.ifPresent(e -> {
                if (originalCategoryId != null) {
                    e.setSpendingCategory(
                        categoryRepo.findById(originalCategoryId).orElse(null));
                } else {
                    e.setSpendingCategory(null);
                }
                e.setEnrichmentSource(originalSource);
                enrichRepo.save(e);
            });
        } else {
            current.ifPresent(enrichRepo::delete);
        }

        auditRepo.deleteAll(
            auditRepo.findBySessionIdOrderByStartedAtAsc(TEST_SESSION));
    }

    @Test
    void recategorizeUpdatesEnrichmentAndWritesAuditRow() {
        long auditCountBefore = auditRepo.count();

        RecategorizeTransactionAction.Output out = executor.run(
            action,
            new RecategorizeTransactionAction.Input(
                testTransactionId, alternativeCategoryId, HIGH_EARNER),
            "AGENT",
            HIGH_EARNER,
            TEST_SESSION
        );

        assertThat(out.enrichmentId())
            .as("output carries the enrichment row id")
            .isNotNull();

        TransactionEnrichment fresh =
            enrichRepo.findByTransactionId(testTransactionId).orElseThrow();
        assertThat(fresh.getSpendingCategory().getId())
            .as("enrichment row points to the new category")
            .isEqualTo(alternativeCategoryId);
        assertThat(fresh.getEnrichmentSource())
            .as("enrichment_source stamped as AGENT_ACTION")
            .isEqualTo(RecategorizeTransactionAction.AGENT_ACTION_SOURCE);

        assertThat(auditRepo.count())
            .as("exactly one audit row written")
            .isEqualTo(auditCountBefore + 1);

        List<ActionAudit> audits =
            auditRepo.findBySessionIdOrderByStartedAtAsc(TEST_SESSION);
        assertThat(audits).hasSize(1);

        ActionAudit audit = audits.get(0);
        assertThat(audit.getActionName()).isEqualTo("RecategorizeTransaction");
        assertThat(audit.getDomain()).isEqualTo("banking");
        assertThat(audit.getActor()).isEqualTo("AGENT");
        assertThat(audit.getOutcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(audit.getUserId()).isEqualTo(HIGH_EARNER);
        assertThat(audit.getSessionId()).isEqualTo(TEST_SESSION);
        assertThat(audit.getInputJson()).contains(testTransactionId.toString());
        assertThat(audit.getOutputJson()).contains(out.enrichmentId().toString());
        assertThat(audit.getEndedAt()).isAfterOrEqualTo(audit.getStartedAt());
    }

    @Test
    void preconditionRejectsForeignTransaction() {
        UUID foreignUser = UUID.fromString("bb000000-0000-0000-0000-000000000000");

        long auditCountBefore = auditRepo.count();

        assertThatThrownBy(() -> executor.run(
            action,
            new RecategorizeTransactionAction.Input(
                testTransactionId, alternativeCategoryId, foreignUser),
            "AGENT",
            foreignUser,
            TEST_SESSION
        ))
            .isInstanceOf(PreconditionViolation.class)
            .hasMessageContaining("does not own transaction");

        assertThat(auditRepo.count())
            .as("audit row written even on precondition failure")
            .isEqualTo(auditCountBefore + 1);

        List<ActionAudit> audits =
            auditRepo.findBySessionIdOrderByStartedAtAsc(TEST_SESSION);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getOutcome())
            .isEqualTo(ActionOutcome.FAILURE_PRECONDITION);
    }
}
