package com.artha.banking.actions;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives {@link DismissAnomalyAction} against an existing flagged
 * anomaly in the eval dataset. The fixture restores the anomaly
 * flag in {@link #tearDown()} so subsequent runs find the same
 * state — the eval pipeline depends on the flagged set staying
 * stable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DismissAnomalyActionTest {

    private static final String TEST_SESSION = "DismissAnomalyActionTest";

    @Autowired private ActionExecutor                  executor;
    @Autowired private DismissAnomalyAction            action;
    @Autowired private TransactionRepository           txRepo;
    @Autowired private TransactionEnrichmentRepository enrichRepo;
    @Autowired private ActionAuditRepository           auditRepo;

    private UUID    transactionId;
    private UUID    actorUserId;
    private String  originalReason;
    private String  originalSource;

    @BeforeEach
    void setUp() {
        List<TransactionEnrichment> anomalies = enrichRepo.findByIsAnomalyTrue();
        assumeTrue(!anomalies.isEmpty(),
            "Eval dataset must contain at least one flagged anomaly");

        TransactionEnrichment fixture = anomalies.get(0);
        transactionId  = fixture.getTransactionId();
        originalReason = fixture.getAnomalyReason();
        originalSource = fixture.getEnrichmentSource();

        Transaction txn = txRepo.findById(transactionId).orElseThrow();
        actorUserId = txn.getUserId();

        auditRepo.deleteAll(
            auditRepo.findBySessionIdOrderByStartedAtAsc(TEST_SESSION));
    }

    @AfterEach
    void tearDown() {
        if (transactionId != null) {
            enrichRepo.findByTransactionId(transactionId).ifPresent(e -> {
                e.setIsAnomaly(true);
                e.setAnomalyReason(originalReason);
                e.setEnrichmentSource(originalSource);
                enrichRepo.save(e);
            });
            auditRepo.deleteAll(
                auditRepo.findBySessionIdOrderByStartedAtAsc(TEST_SESSION));
        }
    }

    @Test
    void clearsAnomalyFlagAndStampsProvenance() {
        long auditCountBefore = auditRepo.count();

        DismissAnomalyAction.Output out = executor.run(
            action,
            new DismissAnomalyAction.Input(
                transactionId, actorUserId,
                "User confirmed: legitimate one-off purchase"),
            "AGENT",
            actorUserId,
            TEST_SESSION
        );

        assertThat(out.enrichmentId()).isNotNull();
        assertThat(out.previousAnomalyReason()).isEqualTo(originalReason);

        TransactionEnrichment fresh =
            enrichRepo.findByTransactionId(transactionId).orElseThrow();
        assertThat(fresh.getIsAnomaly()).isFalse();
        assertThat(fresh.getEnrichmentSource())
            .isEqualTo(RecategorizeTransactionAction.AGENT_ACTION_SOURCE);
        assertThat(fresh.getAnomalyReason())
            .as("anomaly_reason preserved for audit / re-flag")
            .isEqualTo(originalReason);

        assertThat(auditRepo.count()).isEqualTo(auditCountBefore + 1);
        ActionAudit audit = auditRepo
            .findBySessionIdOrderByStartedAtAsc(TEST_SESSION).get(0);
        assertThat(audit.getActionName()).isEqualTo("DismissAnomaly");
        assertThat(audit.getDomain()).isEqualTo("banking");
        assertThat(audit.getOutcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(audit.getInputJson())
            .contains("legitimate one-off purchase");
    }

    @Test
    void rejectsDismissOnNonAnomaly() {
        // Pick any *non*-flagged enrichment row owned by the same user.
        Transaction tx = txRepo.findByUserIdOrderByPostDateDesc(actorUserId)
            .stream()
            .filter(t -> !t.getId().equals(transactionId))
            .filter(t -> enrichRepo.findByTransactionId(t.getId())
                .map(e -> !Boolean.TRUE.equals(e.getIsAnomaly()))
                .orElse(false))
            .findFirst()
            .orElseThrow();

        long auditCountBefore = auditRepo.count();

        assertThatThrownBy(() -> executor.run(
            action,
            new DismissAnomalyAction.Input(tx.getId(), actorUserId, "spurious"),
            "AGENT",
            actorUserId,
            TEST_SESSION
        ))
            .isInstanceOf(PreconditionViolation.class)
            .hasMessageContaining("not flagged as an anomaly");

        assertThat(auditRepo.count()).isEqualTo(auditCountBefore + 1);
    }
}
