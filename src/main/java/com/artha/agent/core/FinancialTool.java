package com.artha.agent.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Contract for all Artha agent tools.
 *
 * Implement this interface + annotate with @ArthaTool to be
 * auto-discovered and registered at startup.
 *
 * Tools are stateless — all state lives in ToolContext.sessionState.
 */
public interface FinancialTool {

    /** Unique tool name — must match what's in getDefinition(). */
    String getName();

    /**
     * Tool definition sent to the LLM.
     * Must include: name, description, input_schema.
     */
    Object getDefinition();

    /**
     * Execute the tool with full context.
     *
     * @param input   JSON input from the LLM
     * @param context Execution context (userId, session state, provider info)
     * @return ToolResult — always return ok() or error(), never throw
     */
    ToolResult execute(JsonNode input, ToolContext context);

    /**
     * Legacy adapter — called by tools that haven't migrated yet.
     * Override execute(JsonNode, ToolContext) in new tools.
     */
    default ToolResult execute(JsonNode input, String userId) {
        return execute(input, ToolContext.of(userId, "legacy", "unknown"));
    }
}