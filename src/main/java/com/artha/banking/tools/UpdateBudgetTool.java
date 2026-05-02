package com.artha.banking.tools;

import com.artha.banking.actions.UpdateBudgetAction;
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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Agent-facing wrapper for {@link UpdateBudgetAction}. */
@Slf4j
@ArthaTool(
    description = "Change the monthly limit on an existing budget",
    category    = "action",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class UpdateBudgetTool implements FinancialTool {

    private final ActionExecutor      executor;
    private final UpdateBudgetAction  action;

    @Override
    public String getName() { return "update_budget"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Adjust the monthly_limit on a budget the user owns.
                Pass the budget UUID and the new positive amount; the
                old limit is returned for confirmation messaging.

                Use only when the user explicitly asks to change a
                limit. Reading a budget's current state goes through
                get_budget_status instead.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "budget_id", Map.of(
                        "type",        "string",
                        "description", "UUID of the budget to update"),
                    "new_monthly_limit", Map.of(
                        "type",        "number",
                        "description", "Positive amount in user's currency")
                ),
                "required", List.of("budget_id", "new_monthly_limit")
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        long startMs = System.currentTimeMillis();
        try {
            UUID userId   = UUID.fromString(context.userId());
            UUID budgetId = UUID.fromString(input.get("budget_id").asText());
            BigDecimal newLimit =
                new BigDecimal(input.get("new_monthly_limit").asText());

            UpdateBudgetAction.Output out = executor.run(
                action,
                new UpdateBudgetAction.Input(budgetId, newLimit, userId),
                "AGENT",
                userId,
                context.sessionId()
            );

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("budget_id",          out.budgetId().toString());
            body.put("previous_limit",     out.previousMonthlyLimit());
            body.put("new_monthly_limit",  newLimit);
            body.put("message",            "Budget limit updated.");
            return ToolResult.okWithTiming(body, startMs);

        } catch (PreconditionViolation pv) {
            log.info("update_budget precondition failed: {}", pv.getMessage());
            return ToolResult.error("Cannot update budget: " + pv.getMessage());
        } catch (PostconditionViolation pv) {
            log.error("update_budget postcondition failed: {}", pv.getMessage());
            return ToolResult.error("Update verification failed: "
                + pv.getMessage() + " (rolled back)");
        } catch (IllegalArgumentException iae) {
            return ToolResult.error("Invalid input: " + iae.getMessage());
        } catch (Exception ex) {
            log.error("update_budget unexpected error", ex);
            return ToolResult.error("Update failed: " + ex.getMessage());
        }
    }
}
