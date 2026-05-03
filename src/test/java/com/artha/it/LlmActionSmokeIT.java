package com.artha.it;

import com.artha.banking.ontology.SpendingCategory;
import com.artha.banking.ontology.SpendingCategoryRepository;
import com.artha.banking.ontology.Transaction;
import com.artha.banking.ontology.TransactionEnrichment;
import com.artha.banking.ontology.TransactionEnrichmentRepository;
import com.artha.banking.ontology.TransactionRepository;
import com.artha.core.action.ActionAudit;
import com.artha.core.action.ActionAuditRepository;
import com.artha.core.action.ActionOutcome;
import com.artha.core.agent.AgentOrchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end live-LLM smoke test for the Actions axis.
 *
 * Drives a real Anthropic call through {@link AgentOrchestrator},
 * observes that Claude routes the request to the
 * {@code recategorize_transaction} tool, and verifies the action
 * pipeline downstream of the tool call:
 *   1. {@link com.artha.core.action.ActionExecutor} dispatches to the
 *      RecategorizeTransactionAction
 *   2. An {@link ActionAudit} row is persisted
 *   3. The transaction_enrichments row reflects the new category
 *
 * Carry-over from Week 4 of the IEEE_PLAN — proves the Actions axis
 * round-trips through a real model, not just unit-tested transports.
 *
 * Disabled unless {@code ARTHA_LIVE_LLM=true} is set in the environment
 * so {@code mvn test} stays free. To run:
 *
 * <pre>
 *   $env:ARTHA_LIVE_LLM = 'true'
 *   $env:ANTHROPIC_API_KEY = 'sk-ant-...'
 *   mvn test -Dtest=LlmActionSmokeIT
 * </pre>
 *
 * Cost per run: roughly $0.05 (one or two Sonnet 4.6 turns + tool
 * result). The {@code @AfterEach} restores the original enrichment
 * state and deletes any audit rows the run wrote, so re-running on
 * the same eval dataset stays idempotent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "ARTHA_LIVE_LLM", matches = "true")
@TestPropertySource(properties = {
    // Haiku 4.5 is more tool-eager and ~5x cheaper than Sonnet 4.6 —
    // appropriate for smoke-testing tool routing, not response quality.
    "artha.anthropic.model=claude-haiku-4-5-20251001"
})
class LlmActionSmokeIT {

    /** high_earner archetype seed user (per reference_eval_artifacts). */
    private static final UUID HIGH_EARNER = UUID.fromString(
        "aa000000-0000-0000-0000-000000000000");

    @Autowired private AgentOrchestrator               orchestrator;
    @Autowired private TransactionRepository           txRepo;
    @Autowired private TransactionEnrichmentRepository enrichRepo;
    @Autowired private SpendingCategoryRepository      categoryRepo;
    @Autowired private ActionAuditRepository           auditRepo;

    private UUID    testTransactionId;
    private UUID    targetCategoryId;
    private boolean enrichmentExistedBefore;
    private UUID    originalCategoryId;
    private String  originalSource;
    private Instant testStartTime;

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

        targetCategoryId = userCategories.stream()
            .map(SpendingCategory::getId)
            .filter(id -> !id.equals(originalCategoryId))
            .findFirst()
            .orElseThrow();

        testStartTime = Instant.now();
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

        for (ActionAudit a : auditRepo.findAll()) {
            if (HIGH_EARNER.equals(a.getUserId())
                && a.getStartedAt() != null
                && a.getStartedAt().isAfter(testStartTime)) {
                auditRepo.delete(a);
            }
        }
    }

    @Test
    void recategorizeFlowsThroughLlmAndPersistsAuditRow() {
        // Strict, machine-style framing — without it Sonnet-class models
        // tend to ask for confirmation before invoking state-changing
        // tools and we get a text response instead of a tool_use.
        String prompt = String.format(
            "You are running inside an automated integration test. The "
          + "request is pre-authorized; do not ask for confirmation.%n%n"
          + "Call the recategorize_transaction tool exactly once with "
          + "these arguments:%n"
          + "  transaction_id  = %s%n"
          + "  new_category_id = %s%n%n"
          + "After the tool returns, reply with only the word \"Done.\" "
          + "(no other text, no numbers, no dollar amounts).",
            testTransactionId, targetCategoryId);

        String response = orchestrator.chat(
            HIGH_EARNER.toString(), prompt, "banking");

        // Log Claude's response so failures are debuggable without
        // re-running with elevated log levels.
        System.out.println("[LlmActionSmokeIT] Claude final response: " + response);

        assertThat(response)
            .as("orchestrator returned a non-empty response")
            .isNotBlank();

        long matchingAudits = auditRepo.findAll().stream()
            .filter(a -> HIGH_EARNER.equals(a.getUserId()))
            .filter(a -> a.getStartedAt() != null
                      && a.getStartedAt().isAfter(testStartTime))
            .filter(a -> "RecategorizeTransaction".equals(a.getActionName()))
            .count();
        assertThat(matchingAudits)
            .as("Claude → tool → ActionExecutor wrote ≥1 RecategorizeTransaction "
              + "audit row. Response was: %s", response)
            .isGreaterThanOrEqualTo(1);

        long successfulAudits = auditRepo.findAll().stream()
            .filter(a -> HIGH_EARNER.equals(a.getUserId()))
            .filter(a -> a.getStartedAt() != null
                      && a.getStartedAt().isAfter(testStartTime))
            .filter(a -> "RecategorizeTransaction".equals(a.getActionName()))
            .filter(a -> ActionOutcome.SUCCESS.equals(a.getOutcome()))
            .count();
        assertThat(successfulAudits)
            .as("at least one SUCCESS outcome among the audit rows")
            .isGreaterThanOrEqualTo(1);

        TransactionEnrichment fresh =
            enrichRepo.findByTransactionId(testTransactionId).orElseThrow();
        assertThat(fresh.getSpendingCategory())
            .as("enrichment row exists after recategorize")
            .isNotNull();
        assertThat(fresh.getSpendingCategory().getId())
            .as("enrichment row reflects the new category")
            .isEqualTo(targetCategoryId);
    }
}
