package com.artha.investments.tools;

import com.artha.core.action.ActionExecutor;
import com.artha.core.action.PostconditionViolation;
import com.artha.core.action.PreconditionViolation;
import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.investments.actions.RecordTradeAction;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bridge tool: agent's record_trade tool-call -> RecordTradeAction. */
@Slf4j
@ArthaTool(
    description = "Append a BUY trade and update the corresponding position + lot",
    category    = "action",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class RecordTradeTool implements FinancialTool {

    private final ActionExecutor      executor;
    private final RecordTradeAction   action;

    @Override public String getName() { return "record_trade"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Record a BUY trade for one of the user's portfolios.
                Appends a trades row, opens a new lot at the trade
                price, and upserts the position (recomputing avg cost
                if the position already exists). The action is
                auditable: every invocation writes one action_audit
                row regardless of outcome. SELL trades are deferred
                to v3; this tool rejects them at precondition.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "portfolio_id", Map.of("type", "string", "description", "UUID of the destination portfolio"),
                    "security_id",  Map.of("type", "string", "description", "UUID of the security"),
                    "side",         Map.of("type", "string", "description", "Currently BUY only"),
                    "quantity",     Map.of("type", "number", "description", "Shares (or fractional shares)"),
                    "price",        Map.of("type", "number", "description", "Per-share execution price"),
                    "fees",         Map.of("type", "number", "description", "Optional commission / fees, default 0"),
                    "executed_at",  Map.of("type", "string", "description", "ISO-8601 timestamp")
                ),
                "required", List.of("portfolio_id", "security_id", "side", "quantity", "price", "executed_at")
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        long startMs = System.currentTimeMillis();
        try {
            UUID userId      = UUID.fromString(context.userId());
            UUID portfolioId = UUID.fromString(input.get("portfolio_id").asText());
            UUID securityId  = UUID.fromString(input.get("security_id").asText());
            String side      = input.get("side").asText().toUpperCase();
            BigDecimal qty   = new BigDecimal(input.get("quantity").asText());
            BigDecimal price = new BigDecimal(input.get("price").asText());
            BigDecimal fees  = input.hasNonNull("fees")
                ? new BigDecimal(input.get("fees").asText()) : BigDecimal.ZERO;
            Instant executedAt = Instant.parse(input.get("executed_at").asText());

            RecordTradeAction.Output out = executor.run(
                action,
                new RecordTradeAction.Input(
                    userId, portfolioId, securityId, side, qty, price, fees, executedAt),
                "AGENT", userId, context.sessionId());

            return ToolResult.okWithTiming(Map.of(
                "trade_id",    out.tradeId().toString(),
                "lot_id",      out.lotId().toString(),
                "position_id", out.positionId().toString(),
                "message",     "Trade recorded; audit row written."
            ), startMs);

        } catch (PreconditionViolation pv) {
            log.info("record_trade precondition failed: {}", pv.getMessage());
            return ToolResult.error("Cannot record trade: " + pv.getMessage());
        } catch (PostconditionViolation pv) {
            log.error("record_trade postcondition failed: {}", pv.getMessage());
            return ToolResult.error("Trade verification failed: " + pv.getMessage()
                + " (transaction was rolled back)");
        } catch (IllegalArgumentException iae) {
            return ToolResult.error("Invalid input: " + iae.getMessage());
        } catch (Exception ex) {
            log.error("record_trade unexpected error", ex);
            return ToolResult.error("Record-trade failed: " + ex.getMessage());
        }
    }
}
