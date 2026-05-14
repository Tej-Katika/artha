package com.artha.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runtime feature flags for A/B experiments and per-axis ablations.
 *
 * <ul>
 *   <li>{@code ontology-tools} (legacy): when false, the enrichment-joined
 *       query paths in anomaly/category/health/subscription tools fall
 *       back to a raw-data baseline.</li>
 *   <li>{@code actions.enabled}: when false, every Action invocation
 *       short-circuits with a "disabled for ablation" error; no
 *       ActionAudit row is written. Used for Condition B in the v2-bls
 *       ablation matrix.</li>
 *   <li>{@code provenance.enabled}: when false, the agent-facing
 *       {@code get_fact_provenance} tool returns a stub indicating the
 *       axis is disabled; the agent loses its citation affordance.
 *       Underlying enrichment/resolver writes still occur (they are
 *       essentially free) but are not surfaced. Used for Condition C.</li>
 *   <li>{@code constraints.enabled}: when false, the orchestrator's
 *       per-turn ConstraintChecker call returns an empty CheckResult;
 *       no repair loop, no ViolationLog writes. Used for Condition D.</li>
 * </ul>
 *
 * All three are wired via env vars ({@code ARTHA_ACTIONS_ENABLED}, etc.)
 * for reproducible ablation runs without modifying application.yml.
 */
@Slf4j
@Component
public class FeatureFlags {

    private final boolean ontologyToolsEnabled;
    private final boolean actionsEnabled;
    private final boolean provenanceEnabled;
    private final boolean constraintsEnabled;

    public FeatureFlags(
        @Value("${artha.ontology.tools-enabled:true}") boolean ontologyToolsEnabled,
        @Value("${artha.actions.enabled:true}")        boolean actionsEnabled,
        @Value("${artha.provenance.enabled:true}")     boolean provenanceEnabled,
        @Value("${artha.constraints.enabled:true}")    boolean constraintsEnabled
    ) {
        this.ontologyToolsEnabled = ontologyToolsEnabled;
        this.actionsEnabled       = actionsEnabled;
        this.provenanceEnabled    = provenanceEnabled;
        this.constraintsEnabled   = constraintsEnabled;

        if (!ontologyToolsEnabled) {
            log.warn("FeatureFlags: ontology-tools DISABLED. "
                + "Anomaly, category-insights, financial-health, and subscription tools "
                + "will bypass the enrichment/ontology layer and return degraded results.");
        }
        if (!actionsEnabled) {
            log.warn("FeatureFlags: ACTIONS axis DISABLED for this run "
                + "(ablation Condition B). All Action invocations will short-circuit.");
        }
        if (!provenanceEnabled) {
            log.warn("FeatureFlags: PROVENANCE axis DISABLED for this run "
                + "(ablation Condition C). get_fact_provenance returns a stub.");
        }
        if (!constraintsEnabled) {
            log.warn("FeatureFlags: CONSTRAINTS axis DISABLED for this run "
                + "(ablation Condition D). No repair loop, no ViolationLog writes.");
        }
    }

    public boolean ontologyToolsEnabled() { return ontologyToolsEnabled; }
    public boolean actionsEnabled()       { return actionsEnabled; }
    public boolean provenanceEnabled()    { return provenanceEnabled; }
    public boolean constraintsEnabled()   { return constraintsEnabled; }
}
