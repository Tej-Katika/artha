package com.artha.banking.tools;

import com.artha.banking.actions.RecategorizeTransactionAction;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bridge tool: agent's tool-call → typed Action invocation.
 *
 * The agent emits a `recategorize_transaction` tool call with JSON
 * args; this tool parses them, dispatches to
 * {@link RecategorizeTransactionAction} via {@link ActionExecutor},
 * and converts the Action's outcome back into a {@link ToolResult}.
 *
 * Three reasons the executor sits between the tool and the action:
 *   1. Append-only ActionAudit row per invocation
 *   2. Hoare-triple soundness check
 *   3. Transactional integrity (rollback on postcondition failure)
 *
 * Errors map to ToolResult.error() so the agent can decide to retry,
 * apologise, or escalate — exceptions never propagate up to the
 * orchestrator.
 */
@Slf4j
@ArthaTool(
    description = "Reassign a transaction to a different spending category",
    category    = "action",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class RecategorizeTransactionTool implements FinancialTool {

    private final ActionExecutor                executor;
    private final RecategorizeTransactionAction action;

    @Override
    public String getName() { return "recategorize_transaction"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Recategorize a single transaction to a different spending
                category. The action is auditable: every invocation
                writes an action_audit row regardless of outcome. The
                user must own both the transaction and the new category;
                ownership is verified as a precondition.

                Use when the user explicitly asks to fix a miscategorized
                transaction. Do NOT use for bulk recategorization or for
                hypothetical "what-if" reasoning — it persists state.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "transaction_id", Map.of(
                        "type",        "string",
                        "description", "UUID of the transaction to recategorize"),
                    "new_category_id", Map.of(
                        "type",        "string",
                        "description", "UUID of the destination SpendingCategory")
                ),
                "required", List.of("transaction_id", "new_category_id")
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        long startMs = System.currentTimeMillis();
        try {
            UUID userId         = UUID.fromString(context.userId());
            UUID transactionId  = UUID.fromString(input.get("transaction_id").asText());
            UUID newCategoryId  = UUID.fromString(input.get("new_category_id").asText());

            RecategorizeTransactionAction.Input actionInput =
                new RecategorizeTransactionAction.Input(
                    transactionId, newCategoryId, userId);

            RecategorizeTransactionAction.Output out = executor.run(
                action,
                actionInput,
                "AGENT",
                userId,
                context.sessionId()
            );

            return ToolResult.okWithTiming(Map.of(
                "enrichment_id",   out.enrichmentId().toString(),
                "old_category_id", out.oldCategoryId() == null
                                       ? null
                                       : out.oldCategoryId().toString(),
                "new_category_id", newCategoryId.toString(),
                "message", "Transaction recategorized; audit row written."
            ), startMs);

        } catch (PreconditionViolation pv) {
            log.info("recategorize_transaction precondition failed: {}", pv.getMessage());
            return ToolResult.error("Cannot recategorize: " + pv.getMessage());
        } catch (PostconditionViolation pv) {
            log.error("recategorize_transaction postcondition failed: {}", pv.getMessage());
            return ToolResult.error("Recategorize verification failed: " + pv.getMessage()
                + " (transaction was rolled back)");
        } catch (IllegalArgumentException iae) {
            return ToolResult.error("Invalid input: " + iae.getMessage());
        } catch (Exception ex) {
            log.error("recategorize_transaction unexpected error", ex);
            return ToolResult.error("Recategorize failed: " + ex.getMessage());
        }
    }
}
