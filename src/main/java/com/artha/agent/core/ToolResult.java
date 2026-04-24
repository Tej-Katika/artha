package com.artha.agent.core;

import java.time.Instant;
import java.util.Map;

/**
 * Standardized result returned by every tool.
 *
 * metadata field carries execution info (duration, cache hit, etc.)
 * without polluting the data payload Claude sees.
 */
public record ToolResult(
    boolean             success,
    Object              data,
    String              errorMessage,
    Instant             retrievedAt,
    Map<String, Object> metadata
) {
    /** Successful result with data. */
    public static ToolResult ok(Object data) {
        return new ToolResult(true, data, null, Instant.now(), Map.of());
    }

    /** Successful result with data and metadata. */
    public static ToolResult ok(Object data, Map<String, Object> metadata) {
        return new ToolResult(true, data, null, Instant.now(), metadata);
    }

    /** Failed result with error message. */
    public static ToolResult error(String message) {
        return new ToolResult(false, null, message, Instant.now(), Map.of());
    }

    /** Execution duration in milliseconds — convenience helper. */
    public static ToolResult okWithTiming(Object data, long startMs) {
        return ok(data, Map.of("duration_ms", System.currentTimeMillis() - startMs));
    }
}