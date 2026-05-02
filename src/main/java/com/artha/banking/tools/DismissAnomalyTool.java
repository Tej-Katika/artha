package com.artha.banking.tools;

import com.artha.banking.actions.DismissAnomalyAction;
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

/**
 * Agent-facing wrapper for {@link DismissAnomalyAction}.
 *
 * Use case: the user reviews a flagged anomaly and decides it is
 * a false positive (e.g., a one-off legitimate large purchase). The
 * dismiss_reason captures their justification and is persisted in
 * the action_audit row.
 */
@Slf4j
@ArthaTool(
    description = "Dismiss a flagged anomaly as a false positive",
    category    = "action",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class DismissAnomalyTool implements FinancialTool {

    private final ActionExecutor          executor;
    private final DismissAnomalyAction    action;

    @Override
    public String getName() { return "dismiss_anomaly"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Mark a flagged anomaly as a false positive. The
                transaction must currently be flagged as an anomaly;
                otherwise the precondition fails.

                Use only when the user has explicitly indicated the
                anomaly is legitimate. The dismiss_reason is stored in
                the audit log to support later review.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "transaction_id", Map.of(
                        "type",        "string",
                        "description", "UUID of the flagged transaction"),
                    "dismiss_reason", Map.of(
                        "type",        "string",
                        "description", "Why the user thinks this is not an anomaly")
                ),
                "required", List.of("transaction_id", "dismiss_reason")
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        long startMs = System.currentTimeMillis();
        try {
            UUID userId        = UUID.fromString(context.userId());
            UUID transactionId = UUID.fromString(input.get("transaction_id").asText());
            String reason      = input.get("dismiss_reason").asText();

            DismissAnomalyAction.Output out = executor.run(
                action,
                new DismissAnomalyAction.Input(transactionId, userId, reason),
                "AGENT",
                userId,
                context.sessionId()
            );

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("enrichment_id",  out.enrichmentId().toString());
            body.put("previous_reason", out.previousAnomalyReason());
            body.put("message",        "Anomaly dismissed; audit row written.");
            return ToolResult.okWithTiming(body, startMs);

        } catch (PreconditionViolation pv) {
            log.info("dismiss_anomaly precondition failed: {}", pv.getMessage());
            return ToolResult.error("Cannot dismiss: " + pv.getMessage());
        } catch (PostconditionViolation pv) {
            log.error("dismiss_anomaly postcondition failed: {}", pv.getMessage());
            return ToolResult.error("Dismiss verification failed: "
                + pv.getMessage() + " (rolled back)");
        } catch (IllegalArgumentException iae) {
            return ToolResult.error("Invalid input: " + iae.getMessage());
        } catch (Exception ex) {
            log.error("dismiss_anomaly unexpected error", ex);
            return ToolResult.error("Dismiss failed: " + ex.getMessage());
        }
    }
}
