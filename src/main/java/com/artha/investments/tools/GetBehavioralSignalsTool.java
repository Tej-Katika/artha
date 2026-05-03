package com.artha.investments.tools;

import com.artha.core.ReferenceDateProvider;
import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.investments.ontology.DailyPriceRepository;
import com.artha.investments.ontology.Portfolio;
import com.artha.investments.ontology.PortfolioRepository;
import com.artha.investments.ontology.Position;
import com.artha.investments.ontology.PositionRepository;
import com.artha.investments.ontology.Trade;
import com.artha.investments.ontology.TradeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Tool: get_behavioral_signals
 * Surfaces three behavioral-bias indicators:
 *   1. concentration_top1_pct — value share of the largest holding
 *   2. concentration_top5_pct — value share of the top 5 holdings
 *   3. churn_rate_per_month   — average trades / month over the window
 *   4. recency_bias_pct       — share of trade volume in the last 30 days
 */
@Slf4j
@ArthaTool(
    description = "Behavioral-bias signals: concentration, churn rate, recency bias",
    category    = "data",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetBehavioralSignalsTool implements FinancialTool {

    private final PortfolioRepository    portfolioRepo;
    private final PositionRepository     positionRepo;
    private final TradeRepository        tradeRepo;
    private final DailyPriceRepository   priceRepo;
    private final ReferenceDateProvider  refDate;

    @Override public String getName() { return "get_behavioral_signals"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Compute behavioral-bias indicators on the user's
                investment activity: top-1 / top-5 holding
                concentration, average monthly churn rate, and
                share of trade volume in the last 30 days
                (recency bias). Use for behavioral_bias_analysis.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "days_back", Map.of("type", "integer",
                        "description", "Window for churn/recency (default 365)")),
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
                ? Math.max(30, input.get("days_back").asInt(365)) : 365;
            Instant to   = refDate.now();
            Instant from = to.minus(daysBack, ChronoUnit.DAYS);
            Instant recencyCutoff = to.minus(30, ChronoUnit.DAYS);

            // Concentration
            BigDecimal totalValue = BigDecimal.ZERO;
            TreeMap<BigDecimal, String> byValueDesc =
                new TreeMap<>((a, b) -> b.compareTo(a));
            for (Portfolio p : portfolioRepo.findByUserId(userId)) {
                for (Position pos : positionRepo.findByPortfolioId(p.getId())) {
                    BigDecimal price = priceRepo
                        .findFirstBySecurityIdOrderByPriceDateDesc(pos.getSecurity().getId())
                        .map(d -> d.getClosePrice()).orElse(null);
                    if (price == null) continue;
                    BigDecimal v = pos.getQuantity().multiply(price);
                    totalValue = totalValue.add(v);
                    byValueDesc.put(v, pos.getSecurity().getTicker());
                }
            }
            BigDecimal top1Pct = pct(firstValue(byValueDesc), totalValue);
            BigDecimal top5Pct = pct(sumFirst(byValueDesc, 5),  totalValue);

            // Churn + recency over the window
            int    totalTrades  = 0;
            BigDecimal totalNotional = BigDecimal.ZERO;
            BigDecimal recentNotional = BigDecimal.ZERO;
            for (Portfolio p : portfolioRepo.findByUserId(userId)) {
                List<Trade> trades = tradeRepo
                    .findByPortfolioIdAndExecutedAtBetweenOrderByExecutedAtDesc(
                        p.getId(), from, to);
                totalTrades += trades.size();
                for (Trade t : trades) {
                    BigDecimal n = t.getQuantity().multiply(t.getPrice());
                    totalNotional = totalNotional.add(n);
                    if (!t.getExecutedAt().isBefore(recencyCutoff)) {
                        recentNotional = recentNotional.add(n);
                    }
                }
            }
            BigDecimal months = BigDecimal.valueOf(daysBack)
                .divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);
            BigDecimal churnPerMonth = months.signum() == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(totalTrades)
                    .divide(months, 2, RoundingMode.HALF_UP);
            BigDecimal recencyPct = pct(recentNotional, totalNotional);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("portfolio_total_value",      totalValue);
            result.put("concentration_top1_pct",     top1Pct);
            result.put("concentration_top5_pct",     top5Pct);
            result.put("trades_in_window",           totalTrades);
            result.put("window_days",                daysBack);
            result.put("churn_trades_per_month",     churnPerMonth);
            result.put("recency_30d_volume_share_pct", recencyPct);
            return ToolResult.okWithTiming(result, start);

        } catch (Exception e) {
            log.error("get_behavioral_signals error: {}", e.getMessage(), e);
            return ToolResult.error(e.getMessage());
        }
    }

    private static BigDecimal firstValue(TreeMap<BigDecimal, String> sortedDesc) {
        return sortedDesc.isEmpty() ? BigDecimal.ZERO : sortedDesc.firstKey();
    }

    private static BigDecimal sumFirst(TreeMap<BigDecimal, String> sortedDesc, int n) {
        BigDecimal sum = BigDecimal.ZERO;
        int i = 0;
        for (BigDecimal v : sortedDesc.keySet()) {
            if (i++ >= n) break;
            sum = sum.add(v);
        }
        return sum;
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) return BigDecimal.ZERO;
        return part.multiply(BigDecimal.valueOf(100))
            .divide(whole, 2, RoundingMode.HALF_UP);
    }
}
