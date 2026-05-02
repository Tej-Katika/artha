package com.artha.banking.actions;

import com.artha.banking.ontology.Transaction;
import com.artha.banking.ontology.TransactionEnrichment;
import com.artha.banking.ontology.TransactionEnrichmentRepository;
import com.artha.banking.ontology.TransactionRepository;
import com.artha.core.action.Action;
import com.artha.core.action.PostconditionViolation;
import com.artha.core.action.PreconditionViolation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Mark a previously-flagged anomaly as a false positive.
 *
 * This is a non-destructive write: only the {@code is_anomaly} flag
 * flips. The {@code anomaly_reason} text is preserved so the
 * statistical detector's original explanation stays available for
 * review (the action_audit row carries the dismissal reason
 * separately).
 *
 * Per research/ONTOLOGY_V2_SPEC.md §4.7 this is one of the six v2
 * banking write Actions. Re-flagging a dismissed anomaly is out of
 * scope for v2; the detector's next batch run will reset the flag if
 * the underlying signal still applies.
 */
@Component
@RequiredArgsConstructor
public class DismissAnomalyAction
        implements Action<DismissAnomalyAction.Input, DismissAnomalyAction.Output> {

    /** Provenance rule_id stamped when this action writes an enrichment. */
    public static final String PROVENANCE_RULE_ID = "ACTION:DismissAnomaly";

    private final TransactionRepository           txRepo;
    private final TransactionEnrichmentRepository enrichRepo;

    public record Input(UUID transactionId,
                        UUID actorUserId,
                        String dismissReason) {
        public Input {
            if (transactionId == null) throw new IllegalArgumentException("transactionId required");
            if (actorUserId   == null) throw new IllegalArgumentException("actorUserId required");
            if (dismissReason == null || dismissReason.isBlank())
                throw new IllegalArgumentException("dismissReason required");
        }
    }

    public record Output(UUID enrichmentId, String previousAnomalyReason) {}

    @Override public String name()   { return "DismissAnomaly"; }
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

        TransactionEnrichment enrichment = enrichRepo
            .findByTransactionId(input.transactionId())
            .orElseThrow(() -> new PreconditionViolation(
                "Transaction has no enrichment row: " + input.transactionId()));

        if (!Boolean.TRUE.equals(enrichment.getIsAnomaly())) {
            throw new PreconditionViolation(
                "Transaction " + input.transactionId()
                + " is not flagged as an anomaly");
        }
    }

    @Override
    public Output execute(Input input) {
        TransactionEnrichment enrichment = enrichRepo
            .findByTransactionId(input.transactionId())
            .orElseThrow(() -> new IllegalStateException(
                "Enrichment vanished between precondition and execute: "
                + input.transactionId()));

        String previousReason = enrichment.getAnomalyReason();
        enrichment.setIsAnomaly(false);
        enrichment.setEnrichmentSource(
            RecategorizeTransactionAction.AGENT_ACTION_SOURCE);
        enrichment.setProvenanceRuleId(PROVENANCE_RULE_ID);
        enrichment.setProvenanceDepsJson(
            "[\"" + input.transactionId() + "\"]");
        enrichment.setProvenanceAsof(Instant.now());

        TransactionEnrichment saved = enrichRepo.save(enrichment);
        return new Output(saved.getId(), previousReason);
    }

    @Override
    public void postcondition(Input input, Output output) {
        TransactionEnrichment fresh = enrichRepo
            .findByTransactionId(input.transactionId())
            .orElseThrow(() -> new PostconditionViolation(
                "Enrichment row missing after dismiss: "
                + input.transactionId()));

        if (Boolean.TRUE.equals(fresh.getIsAnomaly())) {
            throw new PostconditionViolation(
                "is_anomaly still true after dismiss");
        }
        if (!RecategorizeTransactionAction.AGENT_ACTION_SOURCE
                .equals(fresh.getEnrichmentSource())) {
            throw new PostconditionViolation(
                "enrichment_source not stamped — got "
                + fresh.getEnrichmentSource());
        }
        if (!PROVENANCE_RULE_ID.equals(fresh.getProvenanceRuleId())) {
            throw new PostconditionViolation(
                "Provenance rule_id not stamped — got "
                + fresh.getProvenanceRuleId());
        }
        if (fresh.getProvenanceAsof() == null) {
            throw new PostconditionViolation("Provenance asof must be set");
        }
    }
}
