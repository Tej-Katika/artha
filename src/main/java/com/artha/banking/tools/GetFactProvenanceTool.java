package com.artha.banking.tools;

import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.core.provenance.Provenance;
import com.artha.core.provenance.ProvenanceService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Expose the Provenance axis to the agent. Given a fact id (a
 * transaction id, an enrichment id, etc.) returns the structured
 * provenance record: source type, rule id, confidence, dependencies,
 * and the asof timestamp.
 *
 * The agent uses this to answer "why did you classify that as X" or
 * "how confident is that figure" without making things up. Because
 * the tool is read-only it bypasses ActionExecutor — there is no
 * state change to audit.
 */
@Slf4j
@ArthaTool(
    description = "Look up the provenance (source, rule, confidence) of a fact",
    category    = "data",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetFactProvenanceTool implements FinancialTool {

    private final ProvenanceService provenanceService;

    @Override
    public String getName() { return "get_fact_provenance"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Return the provenance of a fact (source type, rule
                that fired, confidence in [0, 1], dependencies, and
                when the derivation happened).

                Pass either a transaction UUID or an enrichment UUID.
                If the fact has no provenance record yet — for example
                an enrichment written before the v2 migration — the
                tool returns the best-effort assembly from the legacy
                source/confidence fields with no rule id.

                Use whenever the user asks "why" or "how do you know"
                a categorization or merchant attribution. Citing the
                rule id and confidence in the response prevents
                ungrounded claims.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "fact_id", Map.of(
                        "type",        "string",
                        "description", "Transaction or enrichment UUID")
                ),
                "required", List.of("fact_id")
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        long startMs = System.currentTimeMillis();
        try {
            UUID factId = UUID.fromString(input.get("fact_id").asText());

            Optional<Provenance> resolved = provenanceService.why(factId);
            if (resolved.isEmpty()) {
                return ToolResult.error(
                    "No provenance record for fact id " + factId);
            }

            Provenance p = resolved.get();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fact_id",     factId.toString());
            body.put("source_type", p.sourceType().name());
            body.put("rule_id",     p.ruleId());     // null if not RULES-derived
            body.put("confidence",  p.confidence());
            body.put("deps",        p.deps().stream().map(UUID::toString).toList());
            body.put("asof",        p.asof().toString());
            return ToolResult.okWithTiming(body, startMs);

        } catch (IllegalArgumentException iae) {
            return ToolResult.error("Invalid fact_id: " + iae.getMessage());
        } catch (Exception ex) {
            log.error("get_fact_provenance unexpected error", ex);
            return ToolResult.error(
                "Provenance lookup failed: " + ex.getMessage());
        }
    }
}
