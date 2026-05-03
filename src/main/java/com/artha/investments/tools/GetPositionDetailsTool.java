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
 * Tool: get_position_details
 * Per-position breakdown: quantity, avg cost, current price, P&L.
 * Optional ticker filter.
 */
@Slf4j
@ArthaTool(
    description = "Per-position breakdown with quantity, cost basis, current price, and unrealized P&L",
    category    = "data",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetPositionDetailsTool implements FinancialTool {

    private final PortfolioRepository  portfolioRepo;
    private final PositionRepository   positionRepo;
    private final DailyPriceRepository priceRepo;

    @Override public String getName() { return "get_position_details"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                List every position the user holds with current market
                value, average cost basis, and unrealized P&L. Use for
                "show me my holdings", "how is X doing", or "what's my
                cost basis on Y".
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "ticker", Map.of(
                        "type", "string",
                        "description",
                        "Optional. If set, only positions in that security.")),
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
            String tickerFilter = input.hasNonNull("ticker")
                ? input.get("ticker").asText().trim().toUpperCase() : null;

            List<Map<String, Object>> rows = new ArrayList<>();
            for (Portfolio p : portfolioRepo.findByUserId(userId)) {
                for (Position pos : positionRepo.findByPortfolioId(p.getId())) {
                    if (tickerFilter != null
                        && !pos.getSecurity().getTicker().equalsIgnoreCase(tickerFilter)) continue;

                    BigDecimal price = priceRepo
                        .findFirstBySecurityIdOrderByPriceDateDesc(pos.getSecurity().getId())
                        .map(d -> d.getClosePrice()).orElse(null);

                    BigDecimal currentValue = price != null
                        ? pos.getQuantity().multiply(price) : null;
                    BigDecimal costBasis    = pos.getQuantity().multiply(pos.getAvgCost());
                    BigDecimal pnl          = currentValue != null
                        ? currentValue.subtract(costBasis) : null;
                    BigDecimal pnlPct       = (pnl != null && costBasis.signum() != 0)
                        ? pnl.multiply(BigDecimal.valueOf(100))
                             .divide(costBasis, 2, RoundingMode.HALF_UP)
                        : null;

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ticker",        pos.getSecurity().getTicker());
                    row.put("name",          pos.getSecurity().getName());
                    row.put("asset_class",   pos.getSecurity().getAssetClass());
                    row.put("portfolio",     p.getName());
                    row.put("quantity",      pos.getQuantity());
                    row.put("avg_cost",      pos.getAvgCost());
                    row.put("current_price", price);
                    row.put("current_value", currentValue);
                    row.put("cost_basis",    costBasis);
                    row.put("unrealized_pnl",     pnl);
                    row.put("unrealized_pnl_pct", pnlPct);
                    rows.add(row);
                }
            }

            return ToolResult.okWithTiming(Map.of(
                "position_count", rows.size(),
                "positions",      rows
            ), start);

        } catch (Exception e) {
            log.error("get_position_details error: {}", e.getMessage(), e);
            return ToolResult.error(e.getMessage());
        }
    }
}
