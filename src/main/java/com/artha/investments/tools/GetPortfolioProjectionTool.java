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
import com.artha.investments.ontology.RiskFreeRate;
import com.artha.investments.ontology.RiskFreeRateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tool: get_portfolio_projection
 * Project a user's actual investment portfolio forward to a target
 * horizon under simple assumptions: monthly contributions, expected
 * real return derived from the latest 10-yr Treasury yield + an
 * equity premium scaled by the portfolio's equity weight.
 *
 * Distinct from the banking-domain {@code get_investment_projection}
 * tool, which is a generic compound-growth calculator on the user's
 * savings rate. This one operates on real positions and asset-class
 * mix.
 *
 * Deliberately simple math (compound at constant rate). v3+ can
 * swap in a Monte Carlo or historical bootstrap; the shape of the
 * output stays the same.
 */
@Slf4j
@ArthaTool(
    description = "Project the user's investment portfolio forward under simple compounding assumptions",
    category    = "data",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetPortfolioProjectionTool implements FinancialTool {

    /** Equity-risk-premium proxy (decimal). Long-run historical ~6%. */
    private static final BigDecimal EQUITY_PREMIUM = new BigDecimal("0.06");
    /** Default fallback for the risk-free rate when FRED data is missing. */
    private static final BigDecimal DEFAULT_RFR    = new BigDecimal("0.04");

    private final PortfolioRepository    portfolioRepo;
    private final PositionRepository     positionRepo;
    private final DailyPriceRepository   priceRepo;
    private final RiskFreeRateRepository rfrRepo;
    private final ReferenceDateProvider  refDate;

    @Override public String getName() { return "get_portfolio_projection"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Project the user's investment portfolio forward to a
                target horizon (default 30 years). Returns expected
                terminal value given monthly_contribution and an
                expected return derived from the current 10-year
                Treasury yield plus an equity-premium contribution
                weighted by the portfolio's equity allocation.
                Use for retirement-target / goal_tracking questions
                grounded in the user's real holdings.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "horizon_years", Map.of("type", "integer",
                        "description", "Years to project (default 30)"),
                    "monthly_contribution", Map.of("type", "number",
                        "description", "USD per month added (default 0)")),
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
            int horizonYears = input.hasNonNull("horizon_years")
                ? Math.max(1, Math.min(input.get("horizon_years").asInt(30), 60))
                : 30;
            BigDecimal monthlyContribution = input.hasNonNull("monthly_contribution")
                ? BigDecimal.valueOf(input.get("monthly_contribution").asDouble(0))
                : BigDecimal.ZERO;

            // Current portfolio value + equity weight
            BigDecimal currentValue = BigDecimal.ZERO;
            BigDecimal equityValue  = BigDecimal.ZERO;
            for (Portfolio p : portfolioRepo.findByUserId(userId)) {
                for (Position pos : positionRepo.findByPortfolioId(p.getId())) {
                    BigDecimal price = priceRepo
                        .findFirstBySecurityIdOrderByPriceDateDesc(pos.getSecurity().getId())
                        .map(d -> d.getClosePrice()).orElse(null);
                    if (price == null) continue;
                    BigDecimal v = pos.getQuantity().multiply(price);
                    currentValue = currentValue.add(v);
                    if (isEquityish(pos.getSecurity().getAssetClass())) {
                        equityValue = equityValue.add(v);
                    }
                }
            }
            BigDecimal equityWeight = currentValue.signum() == 0
                ? BigDecimal.ZERO
                : equityValue.divide(currentValue, 4, RoundingMode.HALF_UP);

            // Expected annual return = rfr + equity_weight * equity_premium
            BigDecimal rfrPct = latestRiskFreeRatePct();
            BigDecimal rfr    = rfrPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            BigDecimal annualReturn = rfr.add(equityWeight.multiply(EQUITY_PREMIUM));

            // FV = PV*(1+r)^n + PMT * (((1+r/12)^(n*12) - 1) / (r/12))
            int months    = horizonYears * 12;
            double r      = annualReturn.doubleValue();
            double rMonth = r / 12.0;
            double pv     = currentValue.doubleValue();
            double pmt    = monthlyContribution.doubleValue();
            double fvLump = pv * Math.pow(1 + r, horizonYears);
            double fvAnnuity = rMonth == 0 ? pmt * months
                : pmt * ((Math.pow(1 + rMonth, months) - 1) / rMonth);
            double fv = fvLump + fvAnnuity;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("horizon_years",        horizonYears);
            result.put("monthly_contribution", monthlyContribution);
            result.put("starting_value",       currentValue);
            result.put("equity_weight",        equityWeight);
            result.put("risk_free_rate_pct",   rfrPct);
            result.put("expected_annual_return_pct",
                annualReturn.multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP));
            result.put("projected_terminal_value",
                BigDecimal.valueOf(fv).setScale(2, RoundingMode.HALF_UP));
            result.put("projected_lump_growth",
                BigDecimal.valueOf(fvLump).setScale(2, RoundingMode.HALF_UP));
            result.put("projected_contribution_growth",
                BigDecimal.valueOf(fvAnnuity).setScale(2, RoundingMode.HALF_UP));

            return ToolResult.okWithTiming(result, start);

        } catch (Exception e) {
            log.error("get_portfolio_projection error: {}", e.getMessage(), e);
            return ToolResult.error(e.getMessage());
        }
    }

    private BigDecimal latestRiskFreeRatePct() {
        return rfrRepo.findFirstByOrderByRateDateDesc()
            .map(RiskFreeRate::getDgs10Pct)
            .orElse(DEFAULT_RFR.multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64));
    }

    private static boolean isEquityish(String assetClass) {
        return "EQUITY".equals(assetClass) || "ETF".equals(assetClass)
            || "CRYPTO".equals(assetClass);
    }
}
