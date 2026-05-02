package com.artha.banking.actions;

import com.artha.banking.ontology.MerchantProfile;
import com.artha.banking.ontology.MerchantProfileRepository;
import com.artha.banking.ontology.RecurringBill;
import com.artha.banking.ontology.RecurringBillRepository;
import com.artha.banking.ontology.Transaction;
import com.artha.banking.ontology.TransactionEnrichment;
import com.artha.banking.ontology.TransactionEnrichmentRepository;
import com.artha.banking.ontology.TransactionRepository;
import com.artha.core.action.Action;
import com.artha.core.action.PostconditionViolation;
import com.artha.core.action.PreconditionViolation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

/**
 * Promote a one-off transaction into a tracked recurring bill.
 *
 * Use case: the auto-detector missed something the user knows is
 * recurring (e.g., a yearly insurance premium that hasn't appeared
 * twice yet). The user supplies the cycle; the action infers
 * expected_amount, merchant, category, and last-seen date from the
 * source transaction.
 *
 * Per research/ONTOLOGY_V2_SPEC.md §4.7. Detection source on the
 * created bill is recorded as MANUAL, distinguishing it from
 * subscriptions surfaced by SubscriptionDetector.
 */
@Component
@RequiredArgsConstructor
public class MarkRecurringAction
        implements Action<MarkRecurringAction.Input, MarkRecurringAction.Output> {

    public static final Set<String> ALLOWED_BILLING_CYCLES =
        Set.of("WEEKLY", "MONTHLY", "ANNUAL");

    private final TransactionRepository           txRepo;
    private final TransactionEnrichmentRepository enrichRepo;
    private final RecurringBillRepository         billRepo;
    private final MerchantProfileRepository       merchantRepo;

    public record Input(UUID transactionId,
                        String billingCycle,
                        UUID actorUserId,
                        String overrideName) {
        public Input {
            if (transactionId == null) throw new IllegalArgumentException("transactionId required");
            if (billingCycle  == null) throw new IllegalArgumentException("billingCycle required");
            if (actorUserId   == null) throw new IllegalArgumentException("actorUserId required");
        }
    }

    public record Output(UUID recurringBillId,
                         UUID merchantProfileId,
                         BigDecimal expectedAmount) {}

    @Override public String name()   { return "MarkRecurring"; }
    @Override public String domain() { return "banking"; }

    @Override
    public void precondition(Input input) {
        Transaction txn = txRepo.findById(input.transactionId())
            .orElseThrow(() -> new PreconditionViolation(
                "Transaction not found: " + input.transactionId()));

        if (!txn.getUserId().equals(input.actorUserId())) {
            throw new PreconditionViolation(
                "Actor " + input.actorUserId()
                + " does not own transaction " + input.transactionId());
        }
        if (!ALLOWED_BILLING_CYCLES.contains(input.billingCycle())) {
            throw new PreconditionViolation(
                "billingCycle must be one of " + ALLOWED_BILLING_CYCLES
                + "; got " + input.billingCycle());
        }

        TransactionEnrichment enrichment = enrichRepo
            .findByTransactionId(input.transactionId())
            .orElseThrow(() -> new PreconditionViolation(
                "Transaction has no enrichment row — cannot infer "
                + "merchant: " + input.transactionId()));

        if (enrichment.getMerchantProfile() == null) {
            throw new PreconditionViolation(
                "Transaction enrichment has no merchant_profile_id — "
                + "the merchant must be canonicalized before marking "
                + "as recurring");
        }

        UUID merchantId = enrichment.getMerchantProfile().getId();
        billRepo.findByUserIdAndMerchantProfileId(
                input.actorUserId(), merchantId)
            .ifPresent(existing -> {
                throw new PreconditionViolation(
                    "RecurringBill already exists for this merchant: "
                    + existing.getId() + " — use update_budget or a "
                    + "future update_recurring action instead");
            });
    }

    @Override
    public Output execute(Input input) {
        Transaction txn = txRepo.findById(input.transactionId())
            .orElseThrow(() -> new IllegalStateException(
                "Transaction vanished: " + input.transactionId()));

        TransactionEnrichment enrichment = enrichRepo
            .findByTransactionId(input.transactionId())
            .orElseThrow(() -> new IllegalStateException(
                "Enrichment vanished: " + input.transactionId()));

        MerchantProfile merchant = enrichment.getMerchantProfile();
        BigDecimal expectedAmount = txn.getAmount().abs();

        RecurringBill bill = new RecurringBill();
        bill.setUserId(input.actorUserId());
        bill.setMerchantProfileId(merchant.getId());
        bill.setName(input.overrideName() != null
            ? input.overrideName()
            : (enrichment.getCanonicalMerchantName() != null
                ? enrichment.getCanonicalMerchantName()
                : txn.getMerchantName()));
        bill.setExpectedAmount(expectedAmount);
        bill.setBillingCycle(input.billingCycle());
        bill.setLastSeenDate(
            txn.getPostDate().atZone(ZoneOffset.UTC).toLocalDate());
        bill.setSpendingCategoryId(
            enrichment.getSpendingCategory() != null
                ? enrichment.getSpendingCategory().getId()
                : null);
        bill.setDetectionSource("MANUAL");
        bill.setIsActive(true);

        RecurringBill saved = billRepo.save(bill);

        enrichment.setRecurringBillId(saved.getId());
        enrichment.setEnrichmentSource(
            RecategorizeTransactionAction.AGENT_ACTION_SOURCE);
        enrichRepo.save(enrichment);

        return new Output(saved.getId(), merchant.getId(), expectedAmount);
    }

    @Override
    public void postcondition(Input input, Output output) {
        RecurringBill fresh = billRepo.findById(output.recurringBillId())
            .orElseThrow(() -> new PostconditionViolation(
                "RecurringBill row missing after save: "
                + output.recurringBillId()));

        if (!input.actorUserId().equals(fresh.getUserId())) {
            throw new PostconditionViolation(
                "user_id mismatch on saved RecurringBill");
        }
        if (!input.billingCycle().equals(fresh.getBillingCycle())) {
            throw new PostconditionViolation(
                "billing_cycle not persisted as expected");
        }
        if (output.expectedAmount().compareTo(fresh.getExpectedAmount()) != 0) {
            throw new PostconditionViolation(
                "expected_amount not persisted as expected");
        }
        if (!"MANUAL".equals(fresh.getDetectionSource())) {
            throw new PostconditionViolation(
                "detection_source must be MANUAL for user-initiated bills");
        }
        if (!Boolean.TRUE.equals(fresh.getIsActive())) {
            throw new PostconditionViolation(
                "new recurring bill must be is_active = true");
        }

        TransactionEnrichment enrichment = enrichRepo
            .findByTransactionId(input.transactionId())
            .orElseThrow(() -> new PostconditionViolation(
                "Enrichment row missing after MarkRecurring"));
        if (!output.recurringBillId().equals(enrichment.getRecurringBillId())) {
            throw new PostconditionViolation(
                "Transaction enrichment recurring_bill_id not linked");
        }
    }
}
