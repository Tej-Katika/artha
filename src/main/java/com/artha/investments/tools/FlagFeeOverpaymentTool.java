package com.artha.investments.tools;

import com.artha.core.action.ActionExecutor;
import com.artha.core.action.PostconditionViolation;
import com.artha.core.action.PreconditionViolation;
import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.investments.actions.FlagFeeOverpaymentAction;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@ArthaTool(
    description = "Annotate a portfolio with a flagged fee overpayment",
    category    = "action",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class FlagFeeOverpaymentTool implements FinancialTool {

    private final ActionExecutor             executor;
    private final FlagFeeOverpaymentAction   action;

    @Override public String getName() { return "flag_fee_overpayment"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Annotate a portfolio with a fee the agent considers an
                overpayment. Appends a fees row prefixed
                "AGENT_FLAGGED:" so audit consumers can distinguish
                agent annotations from operator-reported fees. Existing
                fee rows are not mutated.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "portfolio_id", Map.of("type", "string", "description", "UUID of the portfolio"),
                    "kind",         Map.of("type", "string", "description", "ADVISORY | EXPENSE_RATIO | COMMISSION | SLIPPAGE"),
                    "amount",       Map.of("type", "number", "description", "Fee amount to flag"),
                    "period_start", Map.of("type", "string", "description", "YYYY-MM-DD"),
                    "period_end",   Map.of("type", "string", "description", "YYYY-MM-DD"),
                    "notes",        Map.of("type", "string", "description", "Optional explanation")
                ),
                "required", List.of("portfolio_id", "kind", "amount", "period_start", "period_end")
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        long startMs = System.currentTimeMillis();
        try {
            UUID userId      = UUID.fromString(context.userId());
            UUID portfolioId = UUID.fromString(input.get("portfolio_id").asText());
            String kind   = input.get("kind").asText().toUpperCase();
            BigDecimal amount = new BigDecimal(input.get("amount").asText());
            LocalDate periodStart = LocalDate.parse(input.get("period_start").asText());
            LocalDate periodEnd   = LocalDate.parse(input.get("period_end").asText());
            String notes = input.hasNonNull("notes") ? input.get("notes").asText() : "";

            FlagFeeOverpaymentAction.Output out = executor.run(
                action,
                new FlagFeeOverpaymentAction.Input(
                    userId, portfolioId, kind, amount, periodStart, periodEnd, notes),
                "AGENT", userId, context.sessionId());

            return ToolResult.okWithTiming(Map.of(
                "fee_id",  out.feeId().toString(),
                "message", "Fee overpayment flagged; audit row written."
            ), startMs);

        } catch (PreconditionViolation pv) {
            return ToolResult.error("Cannot flag fee: " + pv.getMessage());
        } catch (PostconditionViolation pv) {
            log.error("flag_fee_overpayment postcondition failed: {}", pv.getMessage());
            return ToolResult.error("Flag-fee verification failed: " + pv.getMessage());
        } catch (IllegalArgumentException iae) {
            return ToolResult.error("Invalid input: " + iae.getMessage());
        } catch (Exception ex) {
            log.error("flag_fee_overpayment unexpected error", ex);
            return ToolResult.error("Flag-fee failed: " + ex.getMessage());
        }
    }
}
