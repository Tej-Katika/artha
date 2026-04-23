package com.finwise.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finwise.agent.core.FinWiseTool;
import com.finwise.agent.core.FinancialTool;
import com.finwise.agent.core.ReferenceDateProvider;
import com.finwise.agent.core.ToolContext;
import com.finwise.agent.core.ToolResult;
import com.finwise.agent.domain.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Tool: get_investment_projection — Phase 8A
 *
 * Projects wealth accumulation based on the user's actual savings rate.
 * Shows what consistent investing would produce over time using
 * compound growth — NOT stock picking or price prediction.
 *
 * Safe approach: education + projection only, never "buy X stock".
 */
@Slf4j
@FinWiseTool(
    description = "Compound growth and retirement projections based on the user's actual savings rate",
    category    = "planning",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetInvestmentProjectionTool implements FinancialTool {

    private final TransactionRepository transactionRepository;
    private final ReferenceDateProvider refDate;

    // Historical average annual returns (conservative estimates)
    private static final Map<String, Double> RETURN_RATES = Map.of(
        "sp500_index_fund",    0.10,  // S&P 500 historical avg ~10%
        "conservative_bonds",  0.04,  // Bond fund avg ~4%
        "balanced_60_40",      0.07,  // 60% stocks / 40% bonds avg ~7%
        "high_yield_savings",  0.048, // Current HYSA ~4.8%
        "money_market",        0.052  // Current money market ~5.2%
    );

    private static final Map<String, String> VEHICLE_DESCRIPTIONS = Map.of(
        "sp500_index_fund",   "S&P 500 Index Fund (e.g. FXAIX, VTI, VTSAX)",
        "conservative_bonds", "Bond Index Fund (e.g. BND, VBTLX)",
        "balanced_60_40",     "Balanced Portfolio (60% stocks / 40% bonds)",
        "high_yield_savings", "High-Yield Savings Account (~4.8% APY)",
        "money_market",       "Money Market Account (~5.2% APY)"
    );

    @Override
    public String getName() { return "get_investment_projection"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Projects future wealth based on the user's actual monthly savings rate,
                showing what consistent investing would produce over different time horizons.
                Compares investment vehicles (S&P 500 index fund, bonds, balanced portfolio,
                high-yield savings). Uses the user's real savings rate from transaction data.
                IMPORTANT: This is educational projection only — not personalized investment advice.
                Does NOT recommend specific stocks or predict market performance.
                Use when the user asks "what would happen if I invested my savings",
                "how much will I have in X years", or "where should I put my money".
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "monthly_amount", Map.of(
                        "type",        "number",
                        "description", "Monthly amount to invest. If not provided, uses actual savings rate."
                    ),
                    "years", Map.of(
                        "type",        "integer",
                        "description", "Projection horizon in years. Default 30. Common: 5, 10, 20, 30, 40."
                    ),
                    "initial_amount", Map.of(
                        "type",        "number",
                        "description", "Initial lump sum to start with. Default 0."
                    ),
                    "vehicle", Map.of(
                        "type",        "string",
                        "description", "Investment vehicle to project: sp500_index_fund, conservative_bonds, balanced_60_40, high_yield_savings, all",
                        "enum",        List.of("sp500_index_fund", "conservative_bonds", "balanced_60_40", "high_yield_savings", "all")
                    )
                ),
                "required", List.of()
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        try {
            long startMs  = System.currentTimeMillis();
            UUID userUUID = UUID.fromString(context.userId());

            // Parse inputs
            int     years         = input.has("years")          ? Math.min(input.path("years").asInt(30), 50)          : 30;
            double  initialAmount = input.has("initial_amount") ? input.path("initial_amount").asDouble(0)             : 0;
            String  vehicle       = input.has("vehicle")        ? input.path("vehicle").asText("all")                  : "all";

            // Get monthly amount from input or derive from actual savings
            double monthlyAmount;
            boolean usingActualSavings = false;

            if (input.has("monthly_amount") && !input.get("monthly_amount").isNull()) {
                monthlyAmount = input.path("monthly_amount").asDouble(0);
            } else {
                // Use actual monthly savings from transaction data
                Instant to   = refDate.now();
                Instant from = to.minus(90, ChronoUnit.DAYS);
                BigDecimal income   = orZero(transactionRepository.totalReceived(userUUID, from, to));
                BigDecimal spending = orZero(transactionRepository.totalSpent(userUUID, from, to));
                BigDecimal netSavings = income.subtract(spending);
                monthlyAmount = netSavings.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP).doubleValue();
                usingActualSavings = true;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("monthly_investment",    Math.max(monthlyAmount, 0));
            result.put("initial_amount",        initialAmount);
            result.put("projection_years",      years);
            result.put("using_actual_savings",  usingActualSavings);
            result.put("disclaimer",
                "Projections are educational estimates based on historical average returns. " +
                "Actual returns vary. Past performance does not guarantee future results. " +
                "This is not personalized investment advice.");

            if (monthlyAmount <= 0) {
                result.put("message",
                    "Current spending exceeds income — no savings available to invest. " +
                    "Focus on reducing spending first to create investable surplus.");
                result.put("tip",
                    "Even $50/month invested consistently over 30 years at 10% avg return = $113,000.");
                return ToolResult.okWithTiming(result, startMs);
            }

            // Build projections
            List<Map<String, Object>> projections = new ArrayList<>();
            List<String> vehiclesToProject = vehicle.equals("all")
                ? List.of("sp500_index_fund", "balanced_60_40", "high_yield_savings")
                : List.of(vehicle);

            for (String v : vehiclesToProject) {
                double rate = RETURN_RATES.getOrDefault(v, 0.07);
                projections.add(buildProjection(v, monthlyAmount, initialAmount, years, rate));
            }

            // Sort by final value descending
            projections.sort((a, b) ->
                Double.compare(
                    ((Number) b.get("final_value")).doubleValue(),
                    ((Number) a.get("final_value")).doubleValue()));

            result.put("projections", projections);

            // Milestone table (5, 10, 20, 30 years) for top vehicle
            if (!projections.isEmpty()) {
                String topVehicle = (String) projections.get(0).get("vehicle_key");
                double topRate = RETURN_RATES.getOrDefault(topVehicle, 0.10);
                result.put("milestones", buildMilestones(monthlyAmount, initialAmount, topRate, years));
                result.put("top_vehicle", projections.get(0).get("vehicle_name"));
            }

            // Cost of waiting analysis
            result.put("cost_of_waiting_1yr", buildCostOfWaiting(monthlyAmount, initialAmount, years, 0.10));

            // What-if scenarios
            result.put("scenarios", buildScenarios(monthlyAmount, initialAmount, years));

            return ToolResult.okWithTiming(result, startMs);

        } catch (Exception e) {
            log.error("GetInvestmentProjectionTool error: {}", e.getMessage());
            return ToolResult.error("Failed to run investment projection: " + e.getMessage());
        }
    }

    // ── Calculation Helpers ───────────────────────────────────────────────────

    private Map<String, Object> buildProjection(
            String vehicleKey, double monthly, double initial, int years, double annualRate) {

        double monthlyRate = annualRate / 12.0;
        int    months      = years * 12;

        // Future value of recurring payments: FV = PMT × ((1+r)^n - 1) / r
        double fvPayments = monthly > 0
            ? monthly * (Math.pow(1 + monthlyRate, months) - 1) / monthlyRate
            : 0;

        // Future value of lump sum: FV = PV × (1+r)^n
        double fvInitial = initial * Math.pow(1 + annualRate, years);

        double finalValue    = fvPayments + fvInitial;
        double totalInvested = monthly * months + initial;
        double totalGrowth   = finalValue - totalInvested;

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("vehicle_key",     vehicleKey);
        p.put("vehicle_name",    VEHICLE_DESCRIPTIONS.getOrDefault(vehicleKey, vehicleKey));
        p.put("annual_return",   Math.round(annualRate * 1000) / 10.0 + "%");
        p.put("final_value",     Math.round(finalValue));
        p.put("total_invested",  Math.round(totalInvested));
        p.put("total_growth",    Math.round(totalGrowth));
        p.put("growth_multiple", Math.round(finalValue / Math.max(totalInvested, 1) * 10) / 10.0);
        p.put("summary", String.format(
            "Invest $%.0f/month for %d years at %.1f%%/yr avg: $%,.0f total " +
            "($%,.0f invested + $%,.0f growth).",
            monthly, years, annualRate * 100, finalValue, totalInvested, totalGrowth));
        return p;
    }

    private List<Map<String, Object>> buildMilestones(
            double monthly, double initial, double rate, int maxYears) {

        List<Map<String, Object>> milestones = new ArrayList<>();
        int[] checkpoints = {1, 5, 10, 20, 30, 40};

        for (int yr : checkpoints) {
            if (yr > maxYears) break;
            double mr = rate / 12.0;
            int    m  = yr * 12;
            double fv = (monthly > 0 ? monthly * (Math.pow(1 + mr, m) - 1) / mr : 0)
                      + initial * Math.pow(1 + rate, yr);
            milestones.add(Map.of(
                "year",          yr,
                "projected_value", Math.round(fv),
                "total_invested",  Math.round(monthly * m + initial),
                "date",          refDate.today().plusYears(yr).toString()
            ));
        }
        return milestones;
    }

    private Map<String, Object> buildCostOfWaiting(
            double monthly, double initial, int years, double rate) {

        // Start now vs start 1 year later
        double mr      = rate / 12.0;
        int    m       = years * 12;
        int    mLate   = (years - 1) * 12;

        double fvNow  = (monthly > 0 ? monthly * (Math.pow(1 + mr, m)     - 1) / mr : 0)
                      + initial * Math.pow(1 + rate, years);
        double fvLate = (monthly > 0 ? monthly * (Math.pow(1 + mr, mLate) - 1) / mr : 0)
                      + initial * Math.pow(1 + rate, years - 1);

        double cost = fvNow - fvLate;
        return Map.of(
            "start_now_value",  Math.round(fvNow),
            "start_1yr_late",   Math.round(fvLate),
            "cost_of_waiting",  Math.round(cost),
            "message", String.format("Waiting just 1 year to start costs $%,.0f in final wealth.", cost)
        );
    }

    private List<Map<String, Object>> buildScenarios(double monthly, double initial, int years) {
        double rate = 0.10;
        double mr   = rate / 12.0;
        int    m    = years * 12;

        List<Map<String, Object>> scenarios = new ArrayList<>();
        double[] amounts = {monthly * 0.5, monthly, monthly * 1.5, monthly * 2};
        String[] labels  = {"Half current savings", "Current savings", "50% more savings", "Double savings"};

        for (int i = 0; i < amounts.length; i++) {
            double fv = (amounts[i] > 0 ? amounts[i] * (Math.pow(1 + mr, m) - 1) / mr : 0)
                       + initial * Math.pow(1 + rate, years);
            scenarios.add(Map.of(
                "label",          labels[i],
                "monthly_amount", Math.round(amounts[i]),
                "final_value",    Math.round(fv)
            ));
        }
        return scenarios;
    }

    private BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}