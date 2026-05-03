package com.artha.banking.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.artha.core.FeatureFlags;
import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.ReferenceDateProvider;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.banking.ontology.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@ArthaTool(
    description = "Detect unusual or anomalous transactions using statistical analysis",
    category    = "ontology",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetAnomalyTool implements FinancialTool {

    private final TransactionEnrichmentRepository enrichmentRepository;
    private final TransactionRepository           transactionRepository;
    private final ReferenceDateProvider           refDate;
    private final FeatureFlags                    flags;

    @Override
    public String getName() { return "get_anomalies"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Detect and return unusual or anomalous transactions.
                Flags transactions where the amount is significantly higher than
                typical for that merchant or category.
                Use this when the user asks about suspicious charges, unusual
                spending, anything out of the ordinary, or potential fraud.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "days_back", Map.of(
                        "type",        "integer",
                        "description", "Number of days to look back (default: 30)"
                    )
                ),
                "required", List.of()
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        try {
            long start    = System.currentTimeMillis();
            UUID userUUID = UUID.fromString(context.userId());
            int daysBack  = input.has("days_back")
                ? input.get("days_back").asInt(30) : 30;

            Instant to   = refDate.now();
            Instant from = to.minusSeconds((long) daysBack * 86400);

            // Ontology-disabled path: this tool has no access to the
            // pre-computed `is_anomaly` flags on enrichments and returns
            // an explicit degraded-mode response.
            if (!flags.ontologyToolsEnabled()) {
                long txScanned = transactionRepository
                    .findByUserIdAndPostDateBetweenOrderByPostDateDesc(userUUID, from, to)
                    .size();
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("anomaly_count",         0);
                raw.put("anomalies",             List.of());
                raw.put("transactions_scanned", txScanned);
                raw.put("days_analyzed",        daysBack);
                raw.put("detection_method",      "raw-baseline (no ontology)");
                raw.put("message",
                    "Anomaly detection is unavailable in raw/ontology-disabled mode. "
                    + "Pre-computed per-merchant baseline statistics require the "
                    + "enrichment layer that is bypassed in this condition.");
                return ToolResult.okWithTiming(raw, start);
            }

            List<TransactionEnrichment> enrichments =
                enrichmentRepository.findByUserIdAndDateRange(userUUID, from, to);

            List<Map<String, Object>> anomalies = enrichments.stream()
                .filter(e -> Boolean.TRUE.equals(e.getIsAnomaly()))
                .map(e -> {
                    Optional<Transaction> txOpt =
                        transactionRepository.findById(e.getTransactionId());

                    Map<String, Object> anomaly = new LinkedHashMap<>();
                    anomaly.put("transaction_id",     e.getTransactionId());
                    anomaly.put("anomaly_reason",     e.getAnomalyReason());
                    anomaly.put("canonical_merchant",
                        e.getCanonicalMerchantName() != null
                            ? e.getCanonicalMerchantName() : "Unknown");

                    txOpt.ifPresent(tx -> {
                        anomaly.put("amount",      tx.getAmount());
                        anomaly.put("date",        tx.getPostDate());
                        anomaly.put("description", tx.getDescription());
                    });

                    return anomaly;
                })
                .collect(Collectors.toList());

            if (anomalies.isEmpty()) {
                // Rich empty-state response: cite what was scanned so the agent
                // (and downstream consumers) can produce informative responses
                // even when nothing is flagged.
                int scanned = enrichments.size();

                java.util.Set<String> merchantSet = enrichments.stream()
                    .map(TransactionEnrichment::getCanonicalMerchantName)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());

                Map<String, Long> merchantCounts = enrichments.stream()
                    .filter(e -> e.getCanonicalMerchantName() != null)
                    .collect(Collectors.groupingBy(
                        TransactionEnrichment::getCanonicalMerchantName,
                        Collectors.counting()));

                List<Map<String, Object>> topMerchants = merchantCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(5)
                    .map(e -> Map.<String, Object>of(
                        "merchant", e.getKey(),
                        "tx_count", e.getValue()))
                    .collect(Collectors.toList());

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("anomaly_count",            0);
                result.put("anomalies",                List.of());
                result.put("transactions_scanned",     scanned);
                result.put("days_analyzed",            daysBack);
                result.put("distinct_merchants",       merchantSet.size());
                result.put("detection_method",         "z-score > 2.5 per-merchant baseline + threshold check");
                result.put("top_merchants_reviewed",   topMerchants);
                result.put("message",
                    "No unusual transactions detected in the last " + daysBack + " days. "
                    + "Scanned " + scanned + " transactions across " + merchantSet.size()
                    + " merchants â€” all amounts within typical per-merchant patterns.");
                return ToolResult.okWithTiming(result, start);
            }

            return ToolResult.okWithTiming(Map.of(
                "anomaly_count", anomalies.size(),
                "anomalies",     anomalies,
                "message",       anomalies.size() + " unusual transaction(s) detected."
            ), start);

        } catch (Exception e) {
            log.error("GetAnomalyTool error: {}", e.getMessage());
            return ToolResult.error("Failed to check anomalies: " + e.getMessage());
        }
    }
}