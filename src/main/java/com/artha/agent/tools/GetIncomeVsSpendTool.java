package com.artha.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.artha.agent.core.ArthaTool;
import com.artha.agent.core.FinancialTool;
import com.artha.agent.core.ReferenceDateProvider;
import com.artha.agent.core.ToolContext;
import com.artha.agent.core.ToolResult;
import com.artha.agent.domain.Transaction;
import com.artha.agent.domain.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Tool: get_income_vs_spend
 * Returns month-by-month income vs spending with savings rate trend.
 */
@Slf4j
@ArthaTool(
    description = "Month-by-month income vs spending comparison with savings rate trend",
    category    = "analytics",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetIncomeVsSpendTool implements FinancialTool {

    private final TransactionRepository transactionRepository;
    private final ObjectMapper          objectMapper;
    private final ReferenceDateProvider refDate;

    @Override
    public String getName() { return "get_income_vs_spend"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", "Month-by-month income vs spending comparison with savings rate trend.",
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "month",       Map.of("type", "string",  "description", "End month YYYY-MM. Optional."),
                    "months_back", Map.of("type", "integer", "description", "Months to look back. Default 3.")
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
            UUID uid = UUID.fromString(userId);
            int monthsBack = input.hasNonNull("months_back") ? Math.min(input.get("months_back").asInt(3), 12) : 3;

            YearMonth endMonth = resolveMonth(input, uid);

            List<Map<String, Object>> monthly = new ArrayList<>();
            double totalIncome = 0;
            double totalSpend  = 0;

            for (int i = monthsBack - 1; i >= 0; i--) {
                YearMonth month = endMonth.minusMonths(i);
                Instant start = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                Instant end   = month.atEndOfMonth().atTime(23, 59, 59).toInstant(ZoneOffset.UTC);

                List<Transaction> txns = transactionRepository
                    .findByUserIdAndPostDateBetweenOrderByPostDateDesc(uid, start, end);

                double income = 0;
                double spend  = 0;
                for (Transaction t : txns) {
                    double amount = t.getAmount().doubleValue();
                    if ("CREDIT".equals(t.getTransactionType()))      income += amount;
                    else if ("DEBIT".equals(t.getTransactionType()))  spend  += amount;
                }

                double net  = income - spend;
                double rate = income > 0 ? (net / income) * 100.0 : 0.0;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("month",        month.toString());
                row.put("income",       Math.round(income * 100.0) / 100.0);
                row.put("spending",     Math.round(spend  * 100.0) / 100.0);
                row.put("net",          Math.round(net    * 100.0) / 100.0);
                row.put("savings_rate", Math.round(rate   * 100.0) / 100.0);
                row.put("status",       net >= 0 ? "SURPLUS" : "DEFICIT");
                monthly.add(row);

                totalIncome += income;
                totalSpend  += spend;
            }

            double avgRate = totalIncome > 0
                ? ((totalIncome - totalSpend) / totalIncome) * 100.0 : 0.0;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("months_analyzed",   monthsBack);
            result.put("total_income",      Math.round(totalIncome * 100.0) / 100.0);
            result.put("total_spending",    Math.round(totalSpend  * 100.0) / 100.0);
            result.put("total_net",         Math.round((totalIncome - totalSpend) * 100.0) / 100.0);
            result.put("avg_savings_rate",  Math.round(avgRate * 100.0) / 100.0);
            result.put("monthly_breakdown", monthly);

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("get_income_vs_spend error — userId={} error={}", userId, e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private YearMonth resolveMonth(JsonNode input, UUID userId) {
        if (input.hasNonNull("month")) {
            try { return YearMonth.parse(input.get("month").asText()); }
            catch (DateTimeParseException ignored) {}
        }
        List<Transaction> recent = transactionRepository.findByUserIdOrderByPostDateDesc(userId);
        if (!recent.isEmpty()) {
            return YearMonth.from(recent.get(0).getPostDate().atZone(ZoneOffset.UTC).toLocalDate());
        }
        return refDate.yearMonth();
    }
}