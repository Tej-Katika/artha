package com.artha.banking.provenance;

import com.artha.banking.actions.RecategorizeTransactionAction;
import com.artha.banking.ontology.SpendingCategory;
import com.artha.banking.ontology.SpendingCategoryRepository;
import com.artha.banking.ontology.Transaction;
import com.artha.banking.ontology.TransactionEnrichment;
import com.artha.banking.ontology.TransactionEnrichmentRepository;
import com.artha.banking.ontology.TransactionRepository;
import com.artha.core.action.ActionAuditRepository;
import com.artha.core.action.ActionExecutor;
import com.artha.core.provenance.Provenance;
import com.artha.core.provenance.ProvenanceService;
import com.artha.core.provenance.SourceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Provenance axis end-to-end check. Verifies:
 *
 * <ol>
 *   <li>Legacy enrichments (no provenance_rule_id stamped) still
 *       return a well-formed Provenance, mapping the
 *       enrichment_source string to a {@link SourceType}.</li>
 *   <li>After {@link RecategorizeTransactionAction} runs, the same
 *       fact resolves to AGENT_ACTION provenance with the action's
 *       rule id and a populated asof timestamp.</li>
 *   <li>{@link ProvenanceService} accepts both enrichment ids and
 *       transaction ids as fact ids.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProvenanceServiceTest {

    private static final UUID HIGH_EARNER = UUID.fromString(
        "aa000000-0000-0000-0000-000000000000");

    private static final String TEST_SESSION = "ProvenanceServiceTest";

    @Autowired private ProvenanceService              provenanceService;
    @Autowired private ActionExecutor                 executor;
    @Autowired private RecategorizeTransactionAction  recategorize;
    @Autowired private TransactionRepository           txRepo;
    @Autowired private TransactionEnrichmentRepository enrichRepo;
    @Autowired private SpendingCategoryRepository      categoryRepo;
    @Autowired private ActionAuditRepository           auditRepo;

    private UUID    transactionId;
    private UUID    alternativeCategoryId;
    private UUID    originalCategoryId;
    private String  originalSource;
    private BigDecimal originalConfidence;
    private String  originalRuleId;
    private String  originalDepsJson;
    private Instant originalAsof;

    @BeforeEach
    void setUp() {
        List<Transaction> txns = txRepo.findByUserIdOrderByPostDateDesc(HIGH_EARNER);
        assumeTrue(!txns.isEmpty(), "Need high_earner transactions");
        transactionId = txns.get(0).getId();

        TransactionEnrichment fixture = enrichRepo
            .findByTransactionId(transactionId).orElseThrow();
        originalCategoryId = fixture.getSpendingCategory() != null
            ? fixture.getSpendingCategory().getId() : null;
        originalSource     = fixture.getEnrichmentSource();
        originalConfidence = fixture.getEnrichmentConfidence();
        originalRuleId     = fixture.getProvenanceRuleId();
        originalDepsJson   = fixture.getProvenanceDepsJson();
        originalAsof       = fixture.getProvenanceAsof();

        List<SpendingCategory> userCategories =
            categoryRepo.findByUserId(HIGH_EARNER);
        assumeTrue(userCategories.size() >= 2, "Need ≥2 categories");
        alternativeCategoryId = userCategories.stream()
            .map(SpendingCategory::getId)
            .filter(id -> !id.equals(originalCategoryId))
            .findFirst()
            .orElseThrow();

        auditRepo.deleteAll(
            auditRepo.findBySessionIdOrderByStartedAtAsc(TEST_SESSION));
    }

    @AfterEach
    void tearDown() {
        if (transactionId == null) return;
        enrichRepo.findByTransactionId(transactionId).ifPresent(e -> {
            if (originalCategoryId != null) {
                e.setSpendingCategory(
                    categoryRepo.findById(originalCategoryId).orElse(null));
            } else {
                e.setSpendingCategory(null);
            }
            e.setEnrichmentSource(originalSource);
            e.setEnrichmentConfidence(originalConfidence);
            e.setProvenanceRuleId(originalRuleId);
            e.setProvenanceDepsJson(originalDepsJson);
            e.setProvenanceAsof(originalAsof);
            enrichRepo.save(e);
        });
        auditRepo.deleteAll(
            auditRepo.findBySessionIdOrderByStartedAtAsc(TEST_SESSION));
    }

    @Test
    void resolvesLegacyEnrichmentByTransactionId() {
        Optional<Provenance> resolved = provenanceService.why(transactionId);

        assertThat(resolved)
            .as("legacy enrichment must still resolve")
            .isPresent();

        Provenance p = resolved.get();
        assertThat(p.sourceType())
            .as("legacy source string '%s' maps to a SourceType", originalSource)
            .isIn(SourceType.RULES, SourceType.METADATA,
                  SourceType.FALLBACK, SourceType.COMPUTED,
                  SourceType.USER_INPUT);
        assertThat(p.confidence()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(p.asof()).isNotNull();
    }

    @Test
    void resolvesByEnrichmentIdAsWell() {
        TransactionEnrichment fixture =
            enrichRepo.findByTransactionId(transactionId).orElseThrow();
        Optional<Provenance> resolved =
            provenanceService.why(fixture.getId());
        assertThat(resolved).isPresent();
    }

    @Test
    void returnsEmptyForUnknownFactId() {
        UUID unknown = UUID.randomUUID();
        assertThat(provenanceService.why(unknown)).isEmpty();
    }

    @Test
    void reflectsAgentActionAfterRecategorize() {
        executor.run(
            recategorize,
            new RecategorizeTransactionAction.Input(
                transactionId, alternativeCategoryId, HIGH_EARNER),
            "AGENT",
            HIGH_EARNER,
            TEST_SESSION
        );

        Provenance after = provenanceService.why(transactionId).orElseThrow();

        assertThat(after.sourceType())
            .as("provenance reflects the agent action")
            .isEqualTo(SourceType.AGENT_ACTION);
        assertThat(after.ruleId())
            .isEqualTo(RecategorizeTransactionAction.PROVENANCE_RULE_ID);
        assertThat(after.deps())
            .as("deps include the source transaction id")
            .containsExactly(transactionId);
        assertThat(after.asof())
            .as("asof was stamped during execute()")
            .isAfterOrEqualTo(Instant.now().minusSeconds(60));
    }
}
