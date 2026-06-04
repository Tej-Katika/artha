package com.artha.core.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Provider-neutral transport for one tool-calling turn.
 *
 * <p>The {@link com.artha.core.agent.AgentOrchestrator} speaks the
 * <em>Anthropic dialect</em> internally: messages are Anthropic content-block
 * arrays ({@code text} / {@code tool_use} / {@code tool_result}), tool
 * definitions carry {@code name} / {@code description} / {@code input_schema},
 * and responses are {@code {stop_reason, content[]}} with stop reasons
 * {@code tool_use} or {@code end_turn}.
 *
 * <p>An implementation for a non-Anthropic provider is therefore an
 * <em>adapter</em>: it translates the Anthropic-shaped request out to its own
 * wire format and translates the provider response back into the
 * Anthropic-shaped {@code {stop_reason, content[]}} the orchestrator expects.
 * This keeps the battle-tested orchestrator loop unchanged across providers —
 * only the transport differs. Used for the cross-family agent ablation
 * (does the absence-signaling gap and its repair hold when a different model
 * family drives the same tools?).
 */
public interface LlmTransport {

    /** Config token selecting this transport: {@code anthropic|openai|gemini}. */
    String provider();

    /**
     * Run one model turn.
     *
     * @param messages Anthropic-shaped conversation (role + content blocks)
     * @param tools    Anthropic-shaped tool definitions (name/description/input_schema)
     * @param system   the system prompt
     * @return an Anthropic-shaped response object
     *         {@code {"stop_reason": "tool_use"|"end_turn", "content": [...]}},
     *         or {@code null} on unrecoverable transport failure.
     */
    JsonNode call(ArrayNode messages, ArrayNode tools, String system);
}
