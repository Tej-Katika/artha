package com.artha.core.constraint;

import java.util.Set;

/**
 * Extracts FactualClaim records from raw agent response text.
 *
 * Strategy is deferred to Week 6 (research/ONTOLOGY_V2_SPEC.md §10.1):
 *   - Regex-based extractor first; if recall < 80% on a labelled
 *     pilot, switch to a structured-output schema where the agent
 *     emits claims as a JSON tool-call alongside the prose.
 *
 * The interface is stable across both strategies.
 */
public interface ClaimExtractor {

    /**
     * Pull factual claims out of `responseText`.
     *
     * @param responseText raw agent response
     * @param domain       "banking" | "investments" — kind set varies by domain
     */
    Set<FactualClaim> extract(String responseText, String domain);
}
