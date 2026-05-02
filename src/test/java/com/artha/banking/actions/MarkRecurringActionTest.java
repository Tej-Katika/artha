package com.artha.banking.actions;

import com.artha.banking.ontology.RecurringBill;
import com.artha.banking.ontology.RecurringBillRepository;
import com.artha.banking.ontology.Transaction;
import com.artha.banking.ontology.TransactionEnrichment;
import com.artha.banking.ontology.TransactionEnrichmentRepository;
import com.artha.banking.ontology.TransactionRepository;
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
 * Drives {@link MarkRecurringAction} against a transaction whose
 * enrichment carries a merchant_profile_id and which is not yet
 * tied to a RecurringBill. Cleanup deletes the bill the test
 * created and clears recurring_bill_id from the source enrichment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MarkRecurringActionTest {

    /** overspender archetype — has merchant-linked enrichments in eval data. */
    private static final UUID OVERSPENDER = UUID.fromString(
        "dd000000-0000-0000-0000-000000000000");

    private static final String TEST_SESSION = "MarkRecurringActionTest";

    @Autowired private ActionExecutor                  executor;
    @Autowired private MarkRecurringAction             action;
    @Autowired private TransactionRepository           txRepo;
    @Autowired private TransactionEnrichmentRepository enrichRepo;
    @Autowired private RecurringBillRepository         billRepo;
    @Autowired private ActionAuditRepository           auditRepo;

    private UUID    candidateTxId;
    private UUID    createdBillId;
    private UUID    originalRecurringBillId;

    @BeforeEach
    void setUp() {
        // Find a transaction that has enrichment with merchant_profile and
        // no existing recurring_bill for that (user, merchant).
        Optional<Transaction> candidate = txRepo
            .findByUserIdOrderByPostDateDesc(OVERSPENDER).stream()
            .filter(t -> {
                Optional<TransactionEnrichment> e =
                    enrichRepo.findByTransactionId(t.getId());
                if (e.isEmpty() || e.get().getMerchantProfile() == null) return false;
                UUID merchantId = e.get().getMerchantProfile().getId();
                return billRepo.findByUserIdAndMerchantProfileId(
                    OVERSPENDER, merchantId).isEmpty();
            })
            .findFirst();

        assumeTrue(candidate.isPresent(),
            "Eval data must include at least one canonicalized transaction "
            + "without an existing recurring bill");

        candidateTxId = candidate.get().getId();
        originalRecurringBillId = enrichRepo.findByTransactionId(candidateTxId)
            .orElseThrow().getRecurringBillId();

        auditRepo.deleteAll(
            auditRepo.findBySessionIdOrderByStartedAtAsc(TEST_SESSION));
    }

    @AfterEach
    void tearDown() {
        // Clear the FK from enrichment FIRST, then delete the bill,
        // otherwise the recurring_bill_id constraint blocks deletion.
        if (candidateTxId != null) {
            enrichRepo.findByTransactionId(candidateTxId).ifPresent(e -> {
                e.setRecurringBillId(originalRecurringBillId);
                enrichRepo.save(e);
            });
        }
        if (createdBillId != null) {
            billRepo.deleteById(createdBillId);
        }
        if (candidateTxId != null) {
            auditRepo.deleteAll(
                auditRepo.findBySessionIdOrderByStartedAtAsc(TEST_SESSION));
        }
    }

    @Test
    void createsRecurringBillAndLinksEnrichment() {
        MarkRecurringAction.Output out = executor.run(
            action,
            new MarkRecurringAction.Input(
                candidateTxId, "MONTHLY", OVERSPENDER, null),
            "AGENT",
            OVERSPENDER,
            TEST_SESSION
        );

        assertThat(out.recurringBillId()).isNotNull();
        createdBillId = out.recurringBillId();

        RecurringBill saved = billRepo.findById(createdBillId).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(OVERSPENDER);
        assertThat(saved.getBillingCycle()).isEqualTo("MONTHLY");
        assertThat(saved.getDetectionSource()).isEqualTo("MANUAL");
        assertThat(saved.getIsActive()).isTrue();
        assertThat(saved.getExpectedAmount())
            .isEqualByComparingTo(out.expectedAmount());

        TransactionEnrichment enrichment =
            enrichRepo.findByTransactionId(candidateTxId).orElseThrow();
        assertThat(enrichment.getRecurringBillId())
            .as("enrichment links to the new bill")
            .isEqualTo(createdBillId);
        assertThat(enrichment.getEnrichmentSource())
            .isEqualTo(RecategorizeTransactionAction.AGENT_ACTION_SOURCE);

        assertThat(auditRepo
            .findBySessionIdOrderByStartedAtAsc(TEST_SESSION))
            .hasSize(1)
            .allSatisfy(a ->
                assertThat(a.getOutcome()).isEqualTo(ActionOutcome.SUCCESS));
    }

    @Test
    void rejectsInvalidBillingCycle() {
        assertThatThrownBy(() -> executor.run(
            action,
            new MarkRecurringAction.Input(
                candidateTxId, "DAILY", OVERSPENDER, null),
            "AGENT",
            OVERSPENDER,
            TEST_SESSION
        ))
            .isInstanceOf(PreconditionViolation.class)
            .hasMessageContaining("billingCycle must be one of");

        // No bill should have been created
        List<RecurringBill> userBills =
            billRepo.findByUserIdAndIsActiveTrue(OVERSPENDER);
        assertThat(userBills).noneMatch(b -> "DAILY".equals(b.getBillingCycle()));
    }
}
