package com.artha.investments.tools;

import com.artha.core.ReferenceDateProvider;
import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.investments.ontology.Portfolio;
import com.artha.investments.ontology.PortfolioRepository;
import com.artha.investments.ontology.Trade;
import com.artha.investments.ontology.TradeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tool: get_trade_history
 * Recent trades for the user's portfolios, filterable by ticker / side
 * / date window.
 */
@Slf4j
@ArthaTool(
    description = "List recent trades across the user's portfolios",
    category    = "data",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetTradeHistoryTool implements FinancialTool {

    private final PortfolioRepository portfolioRepo;
    private final TradeRepository     tradeRepo;
    private final ReferenceDateProvider refDate;

    @Override public String getName() { return "get_trade_history"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                List recent trades — BUYs and SELLs — across the user's
                portfolios. Use for "what trades did I make recently",
                "show my activity", or "did I buy X this month".
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "days_back", Map.of("type", "integer",
                        "description", "Window in days (default 90)"),
                    "ticker",    Map.of("type", "string",
                        "description", "Optional ticker filter"),
                    "side",      Map.of("type", "string",
                        "description", "Optional BUY or SELL filter"),
                    "limit",     Map.of("type", "integer",
                        "description", "Max trades to return (default 25)")),
                "required", List.of()
            )
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, ToolContext context) {
        long start = System.currentTimeMillis();
        try {
            UUID userId = UUID.fromString(context.userId());
            int daysBack = input.hasNonNull("days_back")
                ? Math.max(1, input.get("days_back").asInt(90)) : 90;
            int limit    = input.hasNonNull("limit")
                ? Math.max(1, Math.min(input.get("limit").asInt(25), 200)) : 25;
            String tickerFilter = input.hasNonNull("ticker")
                ? input.get("ticker").asText().trim().toUpperCase() : null;
            String sideFilter = input.hasNonNull("side")
                ? input.get("side").asText().trim().toUpperCase() : null;

            Instant to   = refDate.now();
            Instant from = to.minus(daysBack, ChronoUnit.DAYS);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (Portfolio p : portfolioRepo.findByUserId(userId)) {
                List<Trade> trades = tradeRepo
                    .findByPortfolioIdAndExecutedAtBetweenOrderByExecutedAtDesc(
                        p.getId(), from, to);
                for (Trade t : trades) {
                    if (tickerFilter != null
                        && !t.getSecurity().getTicker().equalsIgnoreCase(tickerFilter)) continue;
                    if (sideFilter != null
                        && !t.getSide().equalsIgnoreCase(sideFilter)) continue;
                    if (rows.size() >= limit) break;

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("trade_id",    t.getId().toString());
                    row.put("portfolio",   p.getName());
                    row.put("ticker",      t.getSecurity().getTicker());
                    row.put("side",        t.getSide());
                    row.put("quantity",    t.getQuantity());
                    row.put("price",       t.getPrice());
                    row.put("fees",        t.getFees());
                    row.put("executed_at", t.getExecutedAt().toString());
                    rows.add(row);
                }
            }

            return ToolResult.okWithTiming(Map.of(
                "trade_count", rows.size(),
                "from",        from.toString(),
                "to",          to.toString(),
                "trades",      rows
            ), start);

        } catch (Exception e) {
            log.error("get_trade_history error: {}", e.getMessage(), e);
            return ToolResult.error(e.getMessage());
        }
    }
}
