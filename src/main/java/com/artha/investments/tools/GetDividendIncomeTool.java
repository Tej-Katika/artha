package com.artha.investments.tools;

import com.artha.core.ReferenceDateProvider;
import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.investments.ontology.Dividend;
import com.artha.investments.ontology.DividendRepository;
import com.artha.investments.ontology.Portfolio;
import com.artha.investments.ontology.PortfolioRepository;
import com.artha.investments.ontology.Position;
import com.artha.investments.ontology.PositionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tool: get_dividend_income
 * Total dividends received over a window, with per-position breakdown.
 */
@Slf4j
@ArthaTool(
    description = "Total dividend cashflow over a window, per position",
    category    = "data",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetDividendIncomeTool implements FinancialTool {

    private final PortfolioRepository    portfolioRepo;
    private final PositionRepository     positionRepo;
    private final DividendRepository     dividendRepo;
    private final ReferenceDateProvider  refDate;

    @Override public String getName() { return "get_dividend_income"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Total dividend income across the user's positions over
                a window, broken down per position. Use for "how much
                in dividends", "what's my dividend yield", or "which
                holdings paid me income".
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "days_back", Map.of("type", "integer",
                        "description", "Window in days (default 365)")),
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
                ? Math.max(1, input.get("days_back").asInt(365)) : 365;
            Instant to   = refDate.now();
            Instant from = to.minus(daysBack, ChronoUnit.DAYS);

            BigDecimal grandTotal = BigDecimal.ZERO;
            List<Map<String, Object>> rows = new ArrayList<>();

            for (Portfolio p : portfolioRepo.findByUserId(userId)) {
                for (Position pos : positionRepo.findByPortfolioId(p.getId())) {
                    List<Dividend> divs = dividendRepo
                        .findByPositionIdAndPaidAtBetweenOrderByPaidAtDesc(
                            pos.getId(), from, to);
                    if (divs.isEmpty()) continue;

                    BigDecimal positionTotal = BigDecimal.ZERO;
                    for (Dividend d : divs) positionTotal = positionTotal.add(d.getAmount());
                    grandTotal = grandTotal.add(positionTotal);

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ticker",       pos.getSecurity().getTicker());
                    row.put("portfolio",    p.getName());
                    row.put("event_count",  divs.size());
                    row.put("total_amount", positionTotal);
                    rows.add(row);
                }
            }

            rows.sort((a, b) -> ((BigDecimal) b.get("total_amount"))
                .compareTo((BigDecimal) a.get("total_amount")));

            return ToolResult.okWithTiming(Map.of(
                "from",            from.toString(),
                "to",              to.toString(),
                "total_income",    grandTotal,
                "position_count",  rows.size(),
                "by_position",     rows
            ), start);

        } catch (Exception e) {
            log.error("get_dividend_income error: {}", e.getMessage(), e);
            return ToolResult.error(e.getMessage());
        }
    }
}
