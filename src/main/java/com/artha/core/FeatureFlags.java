package com.artha.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runtime feature flags. Disabling {@code ontology-tools} switches off
 * the enrichment-joined query paths in the anomaly, category-insights,
 * financial-health, and subscription tools — useful for A/B comparisons
 * between the ontology layer and a raw-data baseline. Set via the
 * {@code ARTHA_ONTOLOGY_TOOLS_ENABLED} env var or {@code application.yml}.
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
            log.warn("FeatureFlags: ontology-tools DISABLED. "
                + "Anomaly, category-insights, financial-health, and subscription tools "
                + "will bypass the enrichment/ontology layer and return degraded results.");
        }
    }

    public boolean ontologyToolsEnabled() {
        return ontologyToolsEnabled;
    }
}
