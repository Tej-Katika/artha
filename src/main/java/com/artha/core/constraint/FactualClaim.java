package com.artha.core.constraint;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A factual assertion extracted from agent response text.
 *
 * Constraints evaluate predicates over a set of these. The structure
 * is intentionally loose at scaffold time — concrete claim types
 * (NumericClaim, CategoryClaim, DateRangeClaim, …) are added in
 * Week 6 once we see what real claims look like in eval responses.
 *
 * @param kind     short type tag, e.g. "spending_amount", "merchant_class",
 *                 "anomaly_count", "date_range", "return_pct"
 * @param subject  primary entity the claim is about (transaction id,
 *                 merchant name, category id, …)
 * @param value    primary numeric value, if any (null otherwise)
 * @param attrs    free-form attribute map for kind-specific fields
 *                 (e.g., {category: "GROCERIES", period_start: "...",
 *                  period_end: "..."})
 * @param spanStart character offset into response text where claim begins
 * @param spanEnd   character offset where claim ends
 */
public record FactualClaim(
    String              kind,
    String              subject,
    BigDecimal          value,
    Map<String, Object> attrs,
    int                 spanStart,
    int                 spanEnd
) {

    public FactualClaim {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind required");
        }
        if (attrs == null) attrs = Map.of();
        else                attrs = Map.copyOf(attrs);
    }
}
