package com.artha.banking.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.banking.ontology.Transaction;
import com.artha.banking.ontology.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Tool: get_transactions
 * Returns a filtered list of transactions for the user.
 */
@Slf4j
@ArthaTool(
    description = "Retrieve a filtered list of individual transactions by category, month, or type",
    category    = "data",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetTransactionsTool implements FinancialTool {

    private final TransactionRepository transactionRepository;
    private final ObjectMapper          objectMapper;

    @Override
    public String getName() { return "get_transactions"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Retrieve a filtered list of individual transactions.
                Use ONLY for listing specific transactions or finding a particular purchase.
                Do NOT use this to calculate totals or category sums â€” use get_spending_summary instead.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "category", Map.of("type", "string",  "description", "Filter by category e.g. FOOD_AND_DRINK. Optional."),
                    "month",    Map.of("type", "string",  "description", "Month in YYYY-MM format. Optional."),
                    "limit",    Map.of("type", "integer", "description", "Max rows to return. Default 20."),
                    "tx_type",  Map.of("type", "string",  "description", "DEBIT or CREDIT. Optional.")
                ),
                "required", List.of()
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        long start = System.currentTimeMillis();
        String json = executeInternal(input, context.userId());
        try {
            JsonNode data = objectMapper.readTree(json);
            if (data.has("error")) return ToolResult.error(data.get("error").asText());
            return ToolResult.okWithTiming(data, start);
        } catch (Exception e) {
            return ToolResult.error("Failed to parse result: " + e.getMessage());
        }
    }

    private String executeInternal(JsonNode input, String userId) {
        try {
            UUID uid    = UUID.fromString(userId);
            String txType    = input.hasNonNull("tx_type")  ? input.get("tx_type").asText(null)  : null;
            String category  = input.hasNonNull("category") ? input.get("category").asText(null) : null;
            int    limit     = input.hasNonNull("limit")    ? Math.min(input.get("limit").asInt(20), 100) : 20;

            Instant startDate = null;
            Instant endDate   = null;
            if (input.hasNonNull("month")) {
                try {
                    YearMonth ym = YearMonth.parse(input.get("month").asText());
                    startDate = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    endDate   = ym.atEndOfMonth().atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException e) {
                    log.warn("Invalid month: {}", input.get("month").asText());
                }
            }

            List<Transaction> txns;
            if (startDate != null) {
                txns = transactionRepository
                    .findByUserIdAndPostDateBetweenOrderByPostDateDesc(uid, startDate, endDate);
            } else {
                txns = transactionRepository.findByUserIdOrderByPostDateDesc(uid);
            }

            // Apply filters
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Transaction t : txns) {
                if (rows.size() >= limit) break;

                if (txType != null && !txType.equalsIgnoreCase(t.getTransactionType())) continue;

                // Category stored in metadata JSONB
                if (category != null) {
                    String txCat = extractCategory(t);
                    if (!category.equalsIgnoreCase(txCat)) continue;
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",               t.getId());
                row.put("date",             t.getPostDate().toString());
                row.put("type",             t.getTransactionType());
                row.put("merchant",         t.getMerchantName());
                row.put("amount",           t.getAmount());
                row.put("category",         extractCategory(t));
                row.put("category_detail",  extractCategoryDetail(t));
                rows.add(row);
            }

            return objectMapper.writeValueAsString(Map.of(
                "count",        rows.size(),
                "transactions", rows
            ));

        } catch (Exception e) {
            log.error("get_transactions error - userId={} error={}", userId, e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private String extractCategory(Transaction t) {
        try {
            if (t.getMetadata() == null) return "UNCATEGORIZED";
            Object cat = t.getMetadata().get("category");
            return cat != null ? cat.toString() : "UNCATEGORIZED";
        } catch (Exception e) {
            return "UNCATEGORIZED";
        }
    }

    private String extractCategoryDetail(Transaction t) {
        try {
            if (t.getMetadata() == null) return "";
            Object detail = t.getMetadata().get("category_detail");
            return detail != null ? detail.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }
}