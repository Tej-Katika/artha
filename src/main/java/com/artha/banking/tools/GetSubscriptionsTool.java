package com.artha.banking.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.artha.core.FeatureFlags;
import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.banking.ontology.*;
import com.artha.banking.enrichment.SubscriptionDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@ArthaTool(
    description = "Detect and list recurring subscriptions and bills from transaction patterns",
    category    = "ontology",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetSubscriptionsTool implements FinancialTool {

    private final RecurringBillRepository recurringBillRepository;
    private final SubscriptionDetector    subscriptionDetector;
    private final FeatureFlags            flags;

    @Override
    public String getName() { return "get_subscriptions"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Detect and list recurring subscriptions and bills.
                Automatically identifies subscription patterns from transaction history
                (e.g. Netflix, Spotify, gym memberships) and shows monthly cost,
                next expected charge date, and total monthly subscription spend.
                Use this when the user asks about subscriptions, recurring charges,
                monthly bills, or wants to find services they might want to cancel.
                """,
            "input_schema", Map.of(
                "type",       "object",
                "properties", Map.of(
                    "refresh", Map.of(
                        "type",        "boolean",
                        "description", "If true, re-scan transactions for new subscriptions. Default false."
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

            // Ontology-disabled path: without the ontology, there is no
            // recurring_bills table and no subscription-detection
            // pipeline. Return a degraded-mode response so the caller
            // can compare against the full ontology path.
            if (!flags.ontologyToolsEnabled()) {
                return ToolResult.okWithTiming(Map.of(
                    "subscription_count", 0,
                    "subscriptions",      List.of(),
                    "total_monthly_cost", BigDecimal.ZERO,
                    "detection_method",   "raw-baseline (no ontology)",
                    "message",
                        "Subscription detection is unavailable in raw/ontology-disabled "
                        + "mode. The recurring-bill ontology and SubscriptionDetector "
                        + "pipeline are bypassed in this condition."
                ), start);
            }

            boolean refresh = input.has("refresh")
                && input.get("refresh").asBoolean(false);

            if (refresh) {
                log.info("Re-scanning subscriptions for user {}", context.userId());
                subscriptionDetector.detectAndPersist(userUUID);
            }

            List<RecurringBill> bills =
                recurringBillRepository.findActiveByUserIdOrderByAmountDesc(userUUID);

            if (bills.isEmpty()) {
                log.info("No recurring bills found â€” running detection");
                subscriptionDetector.detectAndPersist(userUUID);
                bills = recurringBillRepository.findActiveByUserIdOrderByAmountDesc(userUUID);
            }

            if (bills.isEmpty()) {
                return ToolResult.okWithTiming(Map.of(
                    "message",            "No recurring subscriptions detected yet.",
                    "subscriptions",      List.of(),
                    "total_monthly_cost", BigDecimal.ZERO,
                    "tip",                "Add more transaction history for better detection."
                ), start);
            }

            BigDecimal totalMonthlyCost =
                recurringBillRepository.totalMonthlySubscriptionCost(userUUID);

            List<Map<String, Object>> subscriptions = bills.stream()
                .map(bill -> {
                    Map<String, Object> sub = new LinkedHashMap<>();
                    sub.put("name",               bill.getName());
                    sub.put("expected_amount",    bill.getExpectedAmount());
                    sub.put("billing_cycle",      bill.getBillingCycle());
                    sub.put("next_expected_date", bill.getNextExpectedDate() != null
                        ? bill.getNextExpectedDate().toString() : "Unknown");
                    sub.put("last_seen",          bill.getLastSeenDate() != null
                        ? bill.getLastSeenDate().toString() : "Unknown");
                    sub.put("confidence",         bill.getConfidenceScore());
                    sub.put("monthly_equivalent",
                        "ANNUAL".equals(bill.getBillingCycle())
                            ? bill.getExpectedAmount().divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP)
                            : bill.getExpectedAmount());
                    return sub;
                })
                .collect(Collectors.toList());

            return ToolResult.okWithTiming(Map.of(
                "subscription_count", subscriptions.size(),
                "subscriptions",      subscriptions,
                "total_monthly_cost", totalMonthlyCost,
                "annual_equivalent",  totalMonthlyCost.multiply(BigDecimal.valueOf(12))
            ), start);

        } catch (Exception e) {
            log.error("GetSubscriptionsTool error: {}", e.getMessage());
            return ToolResult.error("Failed to get subscriptions: " + e.getMessage());
        }
    }
}