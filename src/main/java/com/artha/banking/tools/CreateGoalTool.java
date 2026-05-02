package com.artha.banking.tools;

import com.artha.banking.actions.CreateGoalAction;
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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent-facing wrapper for {@link CreateGoalAction}.
 *
 * Parses the agent's JSON arguments into a typed Input record,
 * dispatches through ActionExecutor, and translates outcomes back to
 * a ToolResult. Errors do not propagate to the orchestrator.
 */
@Slf4j
@ArthaTool(
    description = "Create a new financial goal (savings / debt payoff / purchase)",
    category    = "action",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class CreateGoalTool implements FinancialTool {

    private final ActionExecutor    executor;
    private final CreateGoalAction  action;

    @Override
    public String getName() { return "create_goal"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Create a new financial goal on behalf of the user.
                Goals fall into one of three types: SAVINGS, DEBT_PAYOFF,
                or PURCHASE. The goal starts in ACTIVE status with a
                current_amount of 0.

                Use this when the user explicitly asks to track a new
                goal. Do not use it as a side-effect of analytical
                questions ("how am I doing on emergency fund?") — for
                those use get_goal_progress instead.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "name",                 Map.of("type", "string",
                        "description", "Display name for the goal, e.g. 'Emergency Fund'"),
                    "goal_type",            Map.of("type", "string",
                        "enum", List.of("SAVINGS", "DEBT_PAYOFF", "PURCHASE"),
                        "description", "Category of goal"),
                    "target_amount",        Map.of("type", "number",
                        "description", "Target amount in user's currency; must be positive"),
                    "target_date",          Map.of("type", "string",
                        "description", "Optional ISO-8601 date (YYYY-MM-DD); must not be in the past"),
                    "monthly_contribution", Map.of("type", "number",
                        "description", "Optional planned contribution per month; non-negative"),
                    "notes",                Map.of("type", "string",
                        "description", "Optional free-form notes")
                ),
                "required", List.of("name", "goal_type", "target_amount")
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        long startMs = System.currentTimeMillis();
        try {
            UUID userId = UUID.fromString(context.userId());

            CreateGoalAction.Input actionInput = new CreateGoalAction.Input(
                userId,
                input.get("name").asText(),
                input.get("goal_type").asText(),
                new BigDecimal(input.get("target_amount").asText()),
                parseDate(input, "target_date"),
                parseDecimal(input, "monthly_contribution"),
                input.hasNonNull("notes") ? input.get("notes").asText() : null
            );

            CreateGoalAction.Output out = executor.run(
                action, actionInput, "AGENT", userId, context.sessionId());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("goal_id", out.goalId().toString());
            body.put("status",  "ACTIVE");
            body.put("message", "Goal created.");
            return ToolResult.okWithTiming(body, startMs);

        } catch (PreconditionViolation pv) {
            log.info("create_goal precondition failed: {}", pv.getMessage());
            return ToolResult.error("Cannot create goal: " + pv.getMessage());
        } catch (PostconditionViolation pv) {
            log.error("create_goal postcondition failed: {}", pv.getMessage());
            return ToolResult.error("Goal creation verification failed: "
                + pv.getMessage() + " (rolled back)");
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            return ToolResult.error("Invalid input: " + ex.getMessage());
        } catch (Exception ex) {
            log.error("create_goal unexpected error", ex);
            return ToolResult.error("Goal creation failed: " + ex.getMessage());
        }
    }

    private static LocalDate parseDate(JsonNode input, String field) {
        if (!input.hasNonNull(field)) return null;
        return LocalDate.parse(input.get(field).asText());
    }

    private static BigDecimal parseDecimal(JsonNode input, String field) {
        if (!input.hasNonNull(field)) return null;
        return new BigDecimal(input.get(field).asText());
    }
}
