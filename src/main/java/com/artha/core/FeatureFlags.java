package com.artha.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ablation-study feature flags for the Artha evaluation.
 * Disabling ontology-tools switches off the enrichment-joined query paths
 * in anomaly, category-insights, financial-health and subscription tools.
 * Set via env var ARTHA_ONTOLOGY_TOOLS_ENABLED or application.yml.
 */
@Slf4j
@Component
public class FeatureFlags {

    private final boolean ontologyToolsEnabled;

    public FeatureFlags(
        @Value("${artha.ontology.tools-enabled:true}") boolean ontologyToolsEnabled
    ) {
        this.ontologyToolsEnabled = ontologyToolsEnabled;
        if (!ontologyToolsEnabled) {
            log.warn("FeatureFlags: ontology-tools DISABLED (ablation mode). "
                + "Anomaly, category-insights, financial-health, and subscription tools "
                + "will bypass the enrichment/ontology layer and return degraded results.");
        }
    }

    public boolean ontologyToolsEnabled() {
        return ontologyToolsEnabled;
    }
}
