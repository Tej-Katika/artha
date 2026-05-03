package com.artha.investments.tools;

import com.artha.core.action.ActionExecutor;
import com.artha.core.action.PostconditionViolation;
import com.artha.core.action.PreconditionViolation;
import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.investments.actions.RecordDividendAction;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@ArthaTool(
    description = "Record a cash dividend received on a position",
    category    = "action",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class RecordDividendTool implements FinancialTool {

    private final ActionExecutor        executor;
    private final RecordDividendAction  action;

    @Override public String getName() { return "record_dividend"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Record a cash-dividend event on a position the user
                holds. Appends a dividends row; the existing position
                is not modified. Use only when the user explicitly
                reports a dividend received.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "position_id", Map.of("type", "string", "description", "UUID of the position"),
                    "amount",      Map.of("type", "number", "description", "Cash amount received"),
                    "currency",    Map.of("type", "string", "description", "Optional ISO currency (default USD)"),
                    "ex_date",     Map.of("type", "string", "description", "Ex-dividend date, YYYY-MM-DD"),
                    "paid_at",     Map.of("type", "string", "description", "ISO-8601 timestamp of payment")
                ),
                "required", List.of("position_id", "amount", "ex_date", "paid_at")
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        long startMs = System.currentTimeMillis();
        try {
            UUID userId     = UUID.fromString(context.userId());
            UUID positionId = UUID.fromString(input.get("position_id").asText());
            BigDecimal amount = new BigDecimal(input.get("amount").asText());
            String currency = input.hasNonNull("currency")
                ? input.get("currency").asText() : "USD";
            LocalDate exDate = LocalDate.parse(input.get("ex_date").asText());
            Instant paidAt   = Instant.parse(input.get("paid_at").asText());

            RecordDividendAction.Output out = executor.run(
                action,
                new RecordDividendAction.Input(
                    userId, positionId, amount, currency, exDate, paidAt),
                "AGENT", userId, context.sessionId());

            return ToolResult.okWithTiming(Map.of(
                "dividend_id", out.dividendId().toString(),
                "message",     "Dividend recorded; audit row written."
            ), startMs);

        } catch (PreconditionViolation pv) {
            return ToolResult.error("Cannot record dividend: " + pv.getMessage());
        } catch (PostconditionViolation pv) {
            log.error("record_dividend postcondition failed: {}", pv.getMessage());
            return ToolResult.error("Dividend verification failed: " + pv.getMessage());
        } catch (IllegalArgumentException iae) {
            return ToolResult.error("Invalid input: " + iae.getMessage());
        } catch (Exception ex) {
            log.error("record_dividend unexpected error", ex);
            return ToolResult.error("Record-dividend failed: " + ex.getMessage());
        }
    }
}
