package com.artha.investments.tools;

import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.investments.ontology.DailyPriceRepository;
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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tool: get_portfolio_summary
 * Returns total value, allocation percentages, and top positions for
 * each of the user's portfolios.
 */
@Slf4j
@ArthaTool(
    description = "Total value, allocation, and top positions for the user's portfolio(s)",
    category    = "data",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetPortfolioSummaryTool implements FinancialTool {

    private final PortfolioRepository  portfolioRepo;
    private final PositionRepository   positionRepo;
    private final DailyPriceRepository priceRepo;

    @Override public String getName() { return "get_portfolio_summary"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Summarize the user's investment portfolios: total market
                value, allocation by asset class, and the top holdings
                by current value. Use for any question about portfolio
                size, composition, or "what do I own".
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "top_n", Map.of(
                        "type", "integer",
                        "description", "How many top holdings to list (default 5)")),
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
            int topN = input.hasNonNull("top_n")
                ? Math.max(1, Math.min(input.get("top_n").asInt(5), 25))
                : 5;

            List<Portfolio> portfolios = portfolioRepo.findByUserId(userId);
            if (portfolios.isEmpty()) {
                return ToolResult.okWithTiming(Map.of(
                    "portfolio_count", 0,
                    "total_value",     0.0,
                    "message",         "No investment portfolios found for this user."
                ), start);
            }

            BigDecimal totalValue = BigDecimal.ZERO;
            Map<String, BigDecimal> byAssetClass = new LinkedHashMap<>();
            List<Map<String, Object>> allPositions = new ArrayList<>();

            for (Portfolio p : portfolios) {
                for (Position pos : positionRepo.findByPortfolioId(p.getId())) {
                    BigDecimal price = latestClose(pos.getSecurity().getId());
                    if (price == null) continue;
                    BigDecimal value = pos.getQuantity().multiply(price);
                    totalValue = totalValue.add(value);

                    String klass = pos.getSecurity().getAssetClass();
                    byAssetClass.merge(klass, value, BigDecimal::add);

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ticker",    pos.getSecurity().getTicker());
                    row.put("portfolio", p.getName());
                    row.put("quantity",  pos.getQuantity());
                    row.put("price",     price);
                    row.put("value",     value);
                    allPositions.add(row);
                }
            }

            allPositions.sort((a, b) -> ((BigDecimal) b.get("value"))
                .compareTo((BigDecimal) a.get("value")));
            List<Map<String, Object>> topHoldings =
                allPositions.subList(0, Math.min(topN, allPositions.size()));

            List<Map<String, Object>> allocation = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> e : byAssetClass.entrySet()) {
                allocation.add(Map.of(
                    "asset_class", e.getKey(),
                    "value",       e.getValue(),
                    "pct",         pct(e.getValue(), totalValue)
                ));
            }

            return ToolResult.okWithTiming(Map.of(
                "portfolio_count", portfolios.size(),
                "total_value",     totalValue,
                "allocation",      allocation,
                "top_holdings",    topHoldings
            ), start);

        } catch (Exception e) {
            log.error("get_portfolio_summary error: {}", e.getMessage(), e);
            return ToolResult.error(e.getMessage());
        }
    }

    private BigDecimal latestClose(UUID securityId) {
        return priceRepo.findFirstBySecurityIdOrderByPriceDateDesc(securityId)
            .map(p -> p.getClosePrice())
            .orElse(null);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) return BigDecimal.ZERO;
        return part.multiply(BigDecimal.valueOf(100))
            .divide(whole, 2, RoundingMode.HALF_UP);
    }
}
