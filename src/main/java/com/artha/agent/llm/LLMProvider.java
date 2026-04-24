package com.artha.agent.llm;

import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Abstraction over any LLM that supports tool calling.
 * Swap providers by changing artha.llm.provider in application.properties.
 */
public interface LLMProvider {

    /** Provider identifier — "claude", "openai", "ollama" */
    String getProviderName();

    /** Whether this provider supports parallel tool calls. */
    boolean supportsToolCalling();

    /**
     * Send messages to the LLM and return the raw JSON response string.
     *
     * @param messages  Conversation history as JSON array
     * @param tools     Tool definitions as JSON array
     * @param systemPrompt System prompt string
     */
    String complete(ArrayNode messages, ArrayNode tools, String systemPrompt)
        throws Exception;
}