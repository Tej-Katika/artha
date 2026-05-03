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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tool: get_trade_anomalies
 * Surfaces unusual trades by size (vs portfolio mean trade size) and
 * by churn (concentration of trades in a short window).
 */
@Slf4j
@ArthaTool(
    description = "Detect unusual trades by size and churn relative to the user's normal pattern",
    category    = "data",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetTradeAnomaliesTool implements FinancialTool {

    /** A trade's notional > MEAN * SIZE_THRESHOLD flags as anomalous. */
    private static final BigDecimal SIZE_THRESHOLD = new BigDecimal("3.0");
    /** Portfolio with > CHURN_THRESHOLD trades in 7 days flags as churning. */
    private static final int CHURN_THRESHOLD = 5;

    private final PortfolioRepository  portfolioRepo;
    private final TradeRepository      tradeRepo;
    private final ReferenceDateProvider refDate;

    @Override public String getName() { return "get_trade_anomalies"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Surface unusual trades: those whose notional is >3x the
                portfolio's mean trade size, plus 7-day windows with
                >5 trades (churn). Use for "any unusual trades", "did
                I overtrade", or "show me red flags".
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
                ? Math.max(7, input.get("days_back").asInt(365)) : 365;
            Instant to   = refDate.now();
            Instant from = to.minus(daysBack, ChronoUnit.DAYS);

            List<Map<String, Object>> oversized = new ArrayList<>();
            List<Map<String, Object>> churnWindows = new ArrayList<>();

            for (Portfolio p : portfolioRepo.findByUserId(userId)) {
                List<Trade> trades = tradeRepo
                    .findByPortfolioIdAndExecutedAtBetweenOrderByExecutedAtDesc(
                        p.getId(), from, to);
                if (trades.isEmpty()) continue;

                BigDecimal totalNotional = BigDecimal.ZERO;
                for (Trade t : trades) {
                    totalNotional = totalNotional.add(t.getQuantity().multiply(t.getPrice()));
                }
                BigDecimal meanNotional = totalNotional
                    .divide(BigDecimal.valueOf(trades.size()), 4, RoundingMode.HALF_UP);
                BigDecimal cutoff = meanNotional.multiply(SIZE_THRESHOLD);

                for (Trade t : trades) {
                    BigDecimal notional = t.getQuantity().multiply(t.getPrice());
                    if (notional.compareTo(cutoff) > 0) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("trade_id",       t.getId().toString());
                        row.put("portfolio",      p.getName());
                        row.put("ticker",         t.getSecurity().getTicker());
                        row.put("side",           t.getSide());
                        row.put("notional",       notional);
                        row.put("portfolio_mean", meanNotional);
                        row.put("multiple",       notional
                            .divide(meanNotional, 2, RoundingMode.HALF_UP));
                        row.put("executed_at",    t.getExecutedAt().toString());
                        oversized.add(row);
                    }
                }

                // Churn: any rolling 7-day window with >CHURN_THRESHOLD trades.
                trades.sort((a, b) -> a.getExecutedAt().compareTo(b.getExecutedAt()));
                for (int i = 0; i < trades.size(); i++) {
                    Instant windowEnd = trades.get(i).getExecutedAt().plus(7, ChronoUnit.DAYS);
                    int count = 0;
                    for (int j = i; j < trades.size(); j++) {
                        if (trades.get(j).getExecutedAt().isAfter(windowEnd)) break;
                        count++;
                    }
                    if (count > CHURN_THRESHOLD) {
                        Map<String, Object> w = new LinkedHashMap<>();
                        w.put("portfolio",   p.getName());
                        w.put("window_start", trades.get(i).getExecutedAt().toString());
                        w.put("trade_count", count);
                        churnWindows.add(w);
                        i += count - 1;   // skip past this window
                    }
                }
            }

            return ToolResult.okWithTiming(Map.of(
                "oversized_trade_count", oversized.size(),
                "oversized_trades",      oversized,
                "churn_window_count",    churnWindows.size(),
                "churn_windows",         churnWindows,
                "size_threshold_x_mean", SIZE_THRESHOLD,
                "churn_window_days",     7,
                "churn_threshold",       CHURN_THRESHOLD
            ), start);

        } catch (Exception e) {
            log.error("get_trade_anomalies error: {}", e.getMessage(), e);
            return ToolResult.error(e.getMessage());
        }
    }
}
