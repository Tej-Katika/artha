package com.artha.banking.tools;

import com.artha.banking.actions.MarkRecurringAction;
import com.artha.core.action.ActionExecutor;
import com.artha.core.action.PostconditionViolation;
import com.artha.core.action.PreconditionViolation;
import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Agent-facing wrapper for {@link MarkRecurringAction}. */
@Slf4j
@ArthaTool(
    description = "Promote a transaction into a tracked recurring bill",
    category    = "action",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class MarkRecurringTool implements FinancialTool {

    private final ActionExecutor       executor;
    private final MarkRecurringAction  action;

    @Override
    public String getName() { return "mark_recurring"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Tell the system that a particular transaction is the
                first observed instance of a recurring bill. The
                action creates a recurring_bill row with
                detection_source = MANUAL and links it back to the
                transaction's enrichment.

                The transaction must already have a merchant profile
                (i.e., enrichment has run). If a recurring bill
                already exists for this merchant under this user,
                the action refuses; use a separate update flow.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "transaction_id", Map.of(
                        "type",        "string",
                        "description", "UUID of the transaction to promote"),
                    "billing_cycle", Map.of(
                        "type",        "string",
                        "enum",        List.of("WEEKLY", "MONTHLY", "ANNUAL"),
                        "description", "How often the user expects this charge"),
                    "name", Map.of(
                        "type",        "string",
                        "description", "Optional override for the bill's display name")
                ),
                "required", List.of("transaction_id", "billing_cycle")
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        long startMs = System.currentTimeMillis();
        try {
            UUID userId        = UUID.fromString(context.userId());
            UUID transactionId = UUID.fromString(input.get("transaction_id").asText());
            String cycle       = input.get("billing_cycle").asText();
            String overrideName = input.hasNonNull("name")
                ? input.get("name").asText()
                : null;

            MarkRecurringAction.Output out = executor.run(
                action,
                new MarkRecurringAction.Input(
                    transactionId, cycle, userId, overrideName),
                "AGENT",
                userId,
                context.sessionId()
            );

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recurring_bill_id",  out.recurringBillId().toString());
            body.put("merchant_profile_id", out.merchantProfileId().toString());
            body.put("expected_amount",    out.expectedAmount());
            body.put("billing_cycle",      cycle);
            body.put("message",            "Recurring bill created.");
            return ToolResult.okWithTiming(body, startMs);

        } catch (PreconditionViolation pv) {
            log.info("mark_recurring precondition failed: {}", pv.getMessage());
            return ToolResult.error("Cannot mark recurring: " + pv.getMessage());
        } catch (PostconditionViolation pv) {
            log.error("mark_recurring postcondition failed: {}", pv.getMessage());
            return ToolResult.error("Mark-recurring verification failed: "
                + pv.getMessage() + " (rolled back)");
        } catch (IllegalArgumentException iae) {
            return ToolResult.error("Invalid input: " + iae.getMessage());
        } catch (Exception ex) {
            log.error("mark_recurring unexpected error", ex);
            return ToolResult.error("Mark-recurring failed: " + ex.getMessage());
        }
    }
}
