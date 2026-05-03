package com.artha.investments.tools;

import com.artha.core.ReferenceDateProvider;
import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.investments.ontology.Fee;
import com.artha.investments.ontology.FeeRepository;
import com.artha.investments.ontology.Portfolio;
import com.artha.investments.ontology.PortfolioRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tool: get_fee_breakdown
 * Total fees by kind (ADVISORY / EXPENSE_RATIO / COMMISSION /
 * SLIPPAGE) over a window, plus a per-portfolio breakdown.
 */
@Slf4j
@ArthaTool(
    description = "Investment fees by kind and portfolio over a date window",
    category    = "data",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetFeeBreakdownTool implements FinancialTool {

    private final PortfolioRepository    portfolioRepo;
    private final FeeRepository          feeRepo;
    private final ReferenceDateProvider  refDate;

    @Override public String getName() { return "get_fee_breakdown"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Sum every fee row touching a window — advisory fees,
                fund expense ratios, commissions, slippage — broken
                down by kind and portfolio. Use for "what fees am I
                paying", "are my costs reasonable", or fee_audit
                queries.
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
            LocalDate to   = refDate.today();
            LocalDate from = to.minus(daysBack, ChronoUnit.DAYS);

            Map<String, BigDecimal> byKind   = new LinkedHashMap<>();
            Map<String, BigDecimal> byPortfolio = new LinkedHashMap<>();
            BigDecimal total = BigDecimal.ZERO;

            for (Portfolio p : portfolioRepo.findByUserId(userId)) {
                BigDecimal portfolioTotal = BigDecimal.ZERO;
                for (Fee f : feeRepo.findByPortfolioId(p.getId())) {
                    if (f.getPeriodEnd().isBefore(from)) continue;
                    if (f.getPeriodStart().isAfter(to))  continue;
                    byKind.merge(f.getKind(), f.getAmount(), BigDecimal::add);
                    portfolioTotal = portfolioTotal.add(f.getAmount());
                    total = total.add(f.getAmount());
                }
                if (portfolioTotal.signum() != 0) {
                    byPortfolio.put(p.getName(), portfolioTotal);
                }
            }

            List<Map<String, Object>> kindRows = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> e : byKind.entrySet()) {
                kindRows.add(Map.of("kind", e.getKey(), "amount", e.getValue()));
            }
            List<Map<String, Object>> portfolioRows = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> e : byPortfolio.entrySet()) {
                portfolioRows.add(Map.of("portfolio", e.getKey(), "amount", e.getValue()));
            }

            return ToolResult.okWithTiming(Map.of(
                "from",         from.toString(),
                "to",           to.toString(),
                "total_fees",   total,
                "by_kind",      kindRows,
                "by_portfolio", portfolioRows
            ), start);

        } catch (Exception e) {
            log.error("get_fee_breakdown error: {}", e.getMessage(), e);
            return ToolResult.error(e.getMessage());
        }
    }
}
