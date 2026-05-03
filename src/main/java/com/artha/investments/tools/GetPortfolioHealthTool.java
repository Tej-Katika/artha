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
import com.artha.investments.ontology.RiskProfile;
import com.artha.investments.ontology.RiskProfileRepository;
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
 * Tool: get_portfolio_health
 * Compares actual asset-class allocation against the portfolio's
 * risk_profile target weights and surfaces drift bands.
 */
@Slf4j
@ArthaTool(
    description = "Actual vs target allocation drift per portfolio (uses risk_profiles target weights)",
    category    = "data",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetPortfolioHealthTool implements FinancialTool {

    /** Drift threshold in absolute percentage points before flagging. */
    private static final BigDecimal DRIFT_BAND_PCT = new BigDecimal("5.00");

    private final PortfolioRepository    portfolioRepo;
    private final PositionRepository     positionRepo;
    private final RiskProfileRepository  riskRepo;
    private final DailyPriceRepository   priceRepo;

    @Override public String getName() { return "get_portfolio_health"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Compare each portfolio's actual asset-class allocation
                against its target risk profile. Flags positions that
                drift > 5 percentage points from target. Use for
                "is my portfolio balanced", "am I overweight equities",
                or "should I rebalance".
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(),
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

            List<Map<String, Object>> portfolioReports = new ArrayList<>();
            for (Portfolio p : portfolioRepo.findByUserId(userId)) {
                RiskProfile risk = riskRepo.findByPortfolioId(p.getId()).orElse(null);
                if (risk == null) continue;

                Map<String, BigDecimal> actualByClass = new LinkedHashMap<>();
                BigDecimal total = BigDecimal.ZERO;
                for (Position pos : positionRepo.findByPortfolioId(p.getId())) {
                    BigDecimal price = priceRepo
                        .findFirstBySecurityIdOrderByPriceDateDesc(pos.getSecurity().getId())
                        .map(d -> d.getClosePrice()).orElse(null);
                    if (price == null) continue;
                    BigDecimal value = pos.getQuantity().multiply(price);
                    total = total.add(value);
                    String bucket = bucketFor(pos.getSecurity().getAssetClass());
                    actualByClass.merge(bucket, value, BigDecimal::add);
                }

                BigDecimal actualEquity = pct(actualByClass.getOrDefault("EQUITY", BigDecimal.ZERO), total);
                BigDecimal actualBond   = pct(actualByClass.getOrDefault("BOND",   BigDecimal.ZERO), total);
                BigDecimal actualAlt    = pct(actualByClass.getOrDefault("ALT",    BigDecimal.ZERO), total);

                List<Map<String, Object>> drifts = new ArrayList<>();
                drifts.add(driftRow("equity", risk.getTargetEquityPct(), actualEquity));
                drifts.add(driftRow("bond",   risk.getTargetBondPct(),   actualBond));
                drifts.add(driftRow("alt",    risk.getTargetAltPct(),    actualAlt));

                long driftedCount = drifts.stream()
                    .filter(d -> Boolean.TRUE.equals(d.get("drifted"))).count();

                Map<String, Object> report = new LinkedHashMap<>();
                report.put("portfolio",                p.getName());
                report.put("archetype",                p.getArchetype());
                report.put("total_value",              total);
                report.put("max_drawdown_tolerance_pct", risk.getMaxDrawdownTolerancePct());
                report.put("allocation_drift",         drifts);
                report.put("drifted_class_count",      driftedCount);
                portfolioReports.add(report);
            }

            return ToolResult.okWithTiming(Map.of(
                "portfolio_count", portfolioReports.size(),
                "portfolios",      portfolioReports,
                "drift_band_pct",  DRIFT_BAND_PCT
            ), start);

        } catch (Exception e) {
            log.error("get_portfolio_health error: {}", e.getMessage(), e);
            return ToolResult.error(e.getMessage());
        }
    }

    /** Map securities asset_class to the 3-bucket model used in
     *  risk_profiles (equity / bond / alt). */
    private static String bucketFor(String assetClass) {
        return switch (assetClass) {
            case "EQUITY", "ETF" -> "EQUITY";
            case "BOND"          -> "BOND";
            case "CRYPTO", "COMMODITY" -> "ALT";
            default              -> "ALT";
        };
    }

    private static Map<String, Object> driftRow(String klass, BigDecimal target, BigDecimal actual) {
        BigDecimal diff = actual.subtract(target);
        boolean drifted = diff.abs().compareTo(DRIFT_BAND_PCT) > 0;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("class",      klass);
        row.put("target_pct", target);
        row.put("actual_pct", actual);
        row.put("diff_pct",   diff);
        row.put("drifted",    drifted);
        return row;
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) return BigDecimal.ZERO;
        return part.multiply(BigDecimal.valueOf(100))
            .divide(whole, 2, RoundingMode.HALF_UP);
    }
}
