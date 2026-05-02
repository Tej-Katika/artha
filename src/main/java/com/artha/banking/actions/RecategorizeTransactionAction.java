package com.artha.banking.actions;

import com.artha.banking.ontology.SpendingCategory;
import com.artha.banking.ontology.SpendingCategoryRepository;
import com.artha.banking.ontology.Transaction;
import com.artha.banking.ontology.TransactionEnrichment;
import com.artha.banking.ontology.TransactionEnrichmentRepository;
import com.artha.banking.ontology.TransactionRepository;
import com.artha.core.action.Action;
import com.artha.core.action.PostconditionViolation;
import com.artha.core.action.PreconditionViolation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Reassign a transaction to a different SpendingCategory.
 *
 * Canonical first Action implementation per
 * research/ONTOLOGY_V2_SPEC.md §4.5. Validates the full Hoare-triple
 * pattern that the rest of the banking action set will template
 * against.
 *
 * Contract:
 *
 *   τ_in  = (transactionId, newCategoryId, actorUserId)
 *   τ_out = (enrichmentId, oldCategoryId)
 *
 *   P : transaction exists ∧ category exists ∧ actor owns both
 *   E : update or create the TransactionEnrichment row to point at
 *       the new category; stamp enrichment_source = "AGENT_ACTION"
 *   Q : re-read the row and verify the category matches the input
 *       and the source is "AGENT_ACTION"
 *
 * The "no side effects on other rows" clause from §4.5 is enforced
 * by construction (the action only calls save() on a single
 * TransactionEnrichment), not by an explicit count assertion.
 */
@Component
@RequiredArgsConstructor
public class RecategorizeTransactionAction
        implements Action<RecategorizeTransactionAction.Input,
                          RecategorizeTransactionAction.Output> {

    /** Indicates the row was last written by an agent action. */
    public static final String AGENT_ACTION_SOURCE = "AGENT_ACTION";

    private final TransactionRepository           txRepo;
    private final TransactionEnrichmentRepository enrichRepo;
    private final SpendingCategoryRepository      categoryRepo;

    public record Input(UUID transactionId,
                        UUID newCategoryId,
                        UUID actorUserId) {
        public Input {
            if (transactionId == null) throw new IllegalArgumentException("transactionId required");
            if (newCategoryId == null) throw new IllegalArgumentException("newCategoryId required");
            if (actorUserId  == null)  throw new IllegalArgumentException("actorUserId required");
        }
    }

    /**
     * @param enrichmentId  the row id touched (existing or newly created)
     * @param oldCategoryId the category the transaction was assigned to before
     *                      this action; null if the transaction had no
     *                      enrichment row or no category previously
     */
    public record Output(UUID enrichmentId, UUID oldCategoryId) {}

    @Override public String name()   { return "RecategorizeTransaction"; }
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

        SpendingCategory cat = categoryRepo.findById(input.newCategoryId())
            .orElseThrow(() -> new PreconditionViolation(
                "SpendingCategory not found: " + input.newCategoryId()));

        if (!cat.getUserId().equals(input.actorUserId())) {
            throw new PreconditionViolation(
                "Category " + input.newCategoryId()
                + " does not belong to actor " + input.actorUserId());
        }
    }

    @Override
    public Output execute(Input input) {
        TransactionEnrichment enrichment = enrichRepo
            .findByTransactionId(input.transactionId())
            .orElseGet(() -> {
                TransactionEnrichment fresh = new TransactionEnrichment();
                fresh.setTransactionId(input.transactionId());
                return fresh;
            });

        UUID oldCategoryId = enrichment.getSpendingCategory() != null
            ? enrichment.getSpendingCategory().getId()
            : null;

        SpendingCategory newCategory = categoryRepo
            .findById(input.newCategoryId())
            .orElseThrow(() -> new IllegalStateException(
                "Category vanished between precondition and execute: "
                + input.newCategoryId()));

        enrichment.setSpendingCategory(newCategory);
        enrichment.setEnrichmentSource(AGENT_ACTION_SOURCE);

        TransactionEnrichment saved = enrichRepo.save(enrichment);
        return new Output(saved.getId(), oldCategoryId);
    }

    @Override
    public void postcondition(Input input, Output output) {
        TransactionEnrichment fresh = enrichRepo
            .findByTransactionId(input.transactionId())
            .orElseThrow(() -> new PostconditionViolation(
                "Enrichment row missing after recategorize for "
                + input.transactionId()));

        UUID actualCategoryId = fresh.getSpendingCategory() != null
            ? fresh.getSpendingCategory().getId()
            : null;

        if (!input.newCategoryId().equals(actualCategoryId)) {
            throw new PostconditionViolation(
                "Category not updated — expected "
                + input.newCategoryId() + ", got " + actualCategoryId);
        }

        if (!AGENT_ACTION_SOURCE.equals(fresh.getEnrichmentSource())) {
            throw new PostconditionViolation(
                "Enrichment source not stamped — got "
                + fresh.getEnrichmentSource());
        }
    }
}
