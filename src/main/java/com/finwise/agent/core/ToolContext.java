package com.finwise.agent.core;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Rich execution context passed to every tool invocation.
 * Replaces the bare (JsonNode input, String userId) signature.
 *
 * Tools can read sessionState set by previous tools in the same
 * conversation, enabling multi-tool coordination without re-querying.
 */
public record ToolContext(
    String              userId,
    String              sessionId,
    Instant             requestTime,
    String              llmProvider,
    Map<String, Object> sessionState
) {
    /** Convenience constructor — sessionState defaults to empty map. */
    public static ToolContext of(String userId, String sessionId, String llmProvider) {
        return new ToolContext(
            userId,
            sessionId,
            Instant.now(),
            llmProvider,
            new HashMap<>()
        );
    }

    /** Store a value in session state for other tools to read. */
    public void put(String key, Object value) {
        sessionState.put(key, value);
    }

    /** Read a value from session state. */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) sessionState.get(key);
    }

    public boolean has(String key) {
        return sessionState.containsKey(key);
    }
}