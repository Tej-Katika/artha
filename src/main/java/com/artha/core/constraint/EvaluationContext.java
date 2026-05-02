package com.artha.core.constraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-query context passed to every Constraint.evaluate() call.
 *
 * Constraints access ontology state via their own injected
 * repositories — the context only carries the request-scoped
 * variables they need (whose data, what reference date, the raw
 * response text for span-aware checks).
 */
public record EvaluationContext(
    UUID    userId,
    Instant referenceDate,
    String  responseText
) {
    public EvaluationContext {
        if (userId == null) throw new IllegalArgumentException("userId required");
        if (referenceDate == null) throw new IllegalArgumentException("referenceDate required");
        if (responseText == null) responseText = "";
    }
}
