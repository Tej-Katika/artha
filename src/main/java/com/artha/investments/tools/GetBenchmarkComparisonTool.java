package com.artha.investments.tools;

import com.artha.core.ReferenceDateProvider;
import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.investments.ontology.Benchmark;
import com.artha.investments.ontology.BenchmarkRepository;
import com.artha.investments.ontology.DailyPrice;
import com.artha.investments.ontology.DailyPriceRepository;
import com.artha.investments.ontology.Portfolio;
import com.artha.investments.ontology.PortfolioRepository;
import com.artha.investments.ontology.Position;
import com.artha.investments.ontology.PositionRepository;
import com.artha.investments.ontology.SecurityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tool: get_benchmark_comparison
 * Computes the user's portfolio total return over a window vs. a
 * named benchmark's total return.
 *
 * Notes:
 *   - Portfolio return uses cost-basis as the denominator (not a TWR
 *     calculation; v3+ should switch to time-weighted returns once
 *     cashflow tracking is in place).
 *   - Benchmark return is close-to-close on the daily_prices series
 *     for the benchmark's ticker if it exists in `securities`. If the
 *     benchmark ticker isn't in the securities universe (most aren't —
 *     SPY, AGG, IEFA aren't fetched by default), the response carries
 *     a benchmark_price_data=false flag and skips the return number.
 */
@Slf4j
@ArthaTool(
    description = "Compare portfolio total return against a named benchmark over a window",
    category    = "data",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetBenchmarkComparisonTool implements FinancialTool {

    private final PortfolioRepository   portfolioRepo;
    private final PositionRepository    positionRepo;
    private final BenchmarkRepository   benchmarkRepo;
    private final SecurityRepository    securityRepo;
    private final DailyPriceRepository  priceRepo;
    private final ReferenceDateProvider refDate;

    @Override public String getName() { return "get_benchmark_comparison"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Compare the user's portfolio total return against a
                named benchmark over a window. Default benchmark is
                SPY. Use for "how am I doing vs the market", "did I
                beat the index", or portfolio_health questions.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "benchmark", Map.of("type", "string",
                        "description", "Benchmark ticker (default SPY)"),
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
            String benchmarkTicker = input.hasNonNull("benchmark")
                ? input.get("benchmark").asText().trim().toUpperCase() : "SPY";
            int daysBack = input.hasNonNull("days_back")
                ? Math.max(1, input.get("days_back").asInt(365)) : 365;
            LocalDate to   = refDate.today();
            LocalDate from = to.minus(daysBack, ChronoUnit.DAYS);

            Benchmark benchmark = benchmarkRepo.findByTicker(benchmarkTicker).orElse(null);

            // Portfolio cost basis vs current value
            BigDecimal currentValue = BigDecimal.ZERO;
            BigDecimal costBasis    = BigDecimal.ZERO;
            for (Portfolio p : portfolioRepo.findByUserId(userId)) {
                for (Position pos : positionRepo.findByPortfolioId(p.getId())) {
                    BigDecimal price = priceRepo
                        .findFirstBySecurityIdOrderByPriceDateDesc(pos.getSecurity().getId())
                        .map(DailyPrice::getClosePrice).orElse(null);
                    if (price == null) continue;
                    currentValue = currentValue.add(pos.getQuantity().multiply(price));
                    costBasis    = costBasis.add(pos.getQuantity().multiply(pos.getAvgCost()));
                }
            }
            BigDecimal portfolioReturnPct = costBasis.signum() == 0
                ? BigDecimal.ZERO
                : currentValue.subtract(costBasis)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(costBasis, 2, RoundingMode.HALF_UP);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("benchmark_ticker", benchmarkTicker);
            result.put("benchmark_known",  benchmark != null);
            result.put("from",             from.toString());
            result.put("to",               to.toString());
            result.put("portfolio_current_value", currentValue);
            result.put("portfolio_cost_basis",    costBasis);
            result.put("portfolio_return_pct",    portfolioReturnPct);

            // Benchmark return only if its ticker is in securities AND
            // we have prices spanning the window.
            BigDecimal benchmarkReturn = computeBenchmarkReturn(benchmarkTicker, from, to);
            result.put("benchmark_price_data", benchmarkReturn != null);
            result.put("benchmark_return_pct", benchmarkReturn);
            if (benchmarkReturn != null) {
                result.put("portfolio_excess_return_pct",
                    portfolioReturnPct.subtract(benchmarkReturn));
            }

            return ToolResult.okWithTiming(result, start);

        } catch (Exception e) {
            log.error("get_benchmark_comparison error: {}", e.getMessage(), e);
            return ToolResult.error(e.getMessage());
        }
    }

    private BigDecimal computeBenchmarkReturn(String ticker, LocalDate from, LocalDate to) {
        return securityRepo.findByTicker(ticker).map(sec -> {
            List<DailyPrice> prices = priceRepo
                .findBySecurityIdAndPriceDateBetweenOrderByPriceDateAsc(sec.getId(), from, to);
            if (prices.size() < 2) return null;
            BigDecimal first = prices.get(0).getClosePrice();
            BigDecimal last  = prices.get(prices.size() - 1).getClosePrice();
            if (first.signum() == 0) return null;
            return last.subtract(first)
                .multiply(BigDecimal.valueOf(100))
                .divide(first, 2, RoundingMode.HALF_UP);
        }).orElse(null);
    }
}
