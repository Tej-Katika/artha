package com.artha.banking.constraints;

import com.artha.core.constraint.ClaimExtractor;
import com.artha.core.constraint.FactualClaim;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starter regex-based claim extractor for the banking domain.
 *
 * Per research/ONTOLOGY_V2_SPEC.md §10.1, the v2 plan begins with a
 * regex extractor; if recall on a labelled pilot drops below 80% we
 * switch to a structured-output schema where the agent emits claims
 * as a JSON tool-call alongside the prose. This implementation pulls
 * out six claim shapes:
 *
 * <ul>
 *   <li>{@code spending_amount} — "$X on Y" or "spent $X"</li>
 *   <li>{@code income_amount}   — "earned $X" or "received $X"</li>
 *   <li>{@code anomaly_count}   — "N anomalies" / "N flagged transactions"</li>
 *   <li>{@code goal_progress}   — "$X toward your $Y goal"</li>
 *   <li>{@code merchant_class}  — "X is a Y" (e.g., "Starbucks is a coffee shop")</li>
 *   <li>{@code date_range}      — "in the last N days/weeks/months/quarters/years"</li>
 * </ul>
 *
 * The patterns intentionally over-match (favour recall over precision)
 * — false positives are absorbed by the constraints themselves
 * (a bogus merchant_class lookup resolves Indeterminate; a sane
 * date_range passes vacuously).
 *
 * No Provenance lookup happens here; this layer is text-only.
 */
@Slf4j
@Component
public class RegexClaimExtractor implements ClaimExtractor {

    /** "$1,234.56" or "$1234" — captures the magnitude only. */
    private static final String AMOUNT_RE = "\\$([0-9][0-9,]*(?:\\.[0-9]{1,2})?)";

    /** Spending claims with a verb prefix — captures amount only. */
    private static final Pattern SPENDING_VERB_PATTERN = Pattern.compile(
        "(?i)(?:spent|paid|charged|cost(?:s)?|expense(?:s)?|outflow)"
        + "[^$]{0,40}" + AMOUNT_RE
    );

    /** Spending claims with category context — captures amount + category. */
    private static final Pattern SPENDING_CATEGORY_PATTERN = Pattern.compile(
        "(?i)" + AMOUNT_RE + "\\s+(?:on|for|at)\\s+([A-Za-z &\\-]{3,40})"
    );

    private static final Pattern INCOME_PATTERN = Pattern.compile(
        "(?i)(?:earned|received|deposited|income|paycheck|inflow|salary)"
        + "[^$]{0,40}" + AMOUNT_RE
    );

    private static final Pattern ANOMALY_COUNT_PATTERN = Pattern.compile(
        "(?i)\\b([0-9]+)\\s+(?:anomal(?:y|ies)|flagged|unusual)\\b"
    );

    /**
     * Goal progress: "$X saved toward your $Y goal" and variants.
     * Allows up to 40 chars on either side of the second amount to
     * absorb prose like "emergency fund goal" or "retirement target".
     */
    private static final Pattern GOAL_PROGRESS_PATTERN = Pattern.compile(
        "(?i)" + AMOUNT_RE
        + "\\s+(?:saved|of|toward(?:s)?)[^$]{0,40}" + AMOUNT_RE
        + "[^$]{0,40}(?:goal|target|fund|savings)"
    );

    /**
     * "X is a Y" — captures merchant name + claimed class. Liberal:
     * matches non-merchant uses too ("Saving is a habit"). The
     * MerchantClassMatchConstraint resolves false positives by
     * looking the subject up in MerchantProfile and returning
     * Indeterminate when the merchant is not in the ontology.
     *
     * The class is bounded to at most two words to avoid slurping
     * trailing prose ("coffee shop you visited" → "coffee shop").
     */
    private static final Pattern MERCHANT_CLASS_PATTERN = Pattern.compile(
        "\\b([A-Z][\\w&'\\-]*(?:\\s+[\\w&'\\-]+){0,4}?)\\s+is\\s+(?:a|an)\\s+"
        + "([A-Za-z][A-Za-z&'\\-]*(?:\\s+[A-Za-z][A-Za-z&'\\-]*)?)"
    );

    /** "last 30 days", "in the past 6 months", "previous 2 quarters" — captures count + unit. */
    private static final Pattern DATE_RANGE_NUMERIC_PATTERN = Pattern.compile(
        "(?i)(?:in\\s+the\\s+)?(?:last|past|previous)\\s+([0-9]+)\\s+(day|week|month|quarter|year)s?\\b"
    );

    /** "last week", "last quarter" — bare unit, count defaults to 1. */
    private static final Pattern DATE_RANGE_BARE_PATTERN = Pattern.compile(
        "(?i)\\b(?:in\\s+the\\s+)?(?:last|past|previous)\\s+(week|month|quarter|year)\\b(?!\\s+[0-9])"
    );

    @Override
    public Set<FactualClaim> extract(String responseText, String domain) {
        Set<FactualClaim> claims = new HashSet<>();
        if (responseText == null || responseText.isBlank()) return claims;
        if (!"banking".equals(domain)) return claims;

        addSpendingClaims(responseText, claims);
        addIncomeClaims(responseText, claims);
        addAnomalyCountClaims(responseText, claims);
        addGoalProgressClaims(responseText, claims);
        addMerchantClassClaims(responseText, claims);
        addDateRangeClaims(responseText, claims);

        log.debug("Extracted {} claim(s) from {} chars of banking response",
            claims.size(), responseText.length());
        return claims;
    }

    private static void addSpendingClaims(String text, Set<FactualClaim> out) {
        Matcher m = SPENDING_VERB_PATTERN.matcher(text);
        while (m.find()) {
            BigDecimal value = parseAmount(m.group(1));
            if (value == null) continue;
            out.add(new FactualClaim(
                "spending_amount", "user", value, Map.of(),
                m.start(), m.end()));
        }

        Matcher cat = SPENDING_CATEGORY_PATTERN.matcher(text);
        while (cat.find()) {
            BigDecimal value = parseAmount(cat.group(1));
            if (value == null) continue;
            String category = cat.group(2);
            Map<String, Object> attrs = new LinkedHashMap<>();
            if (category != null) attrs.put("category", category.trim());
            out.add(new FactualClaim(
                "spending_amount", "user", value, attrs,
                cat.start(), cat.end()));
        }
    }

    private static void addIncomeClaims(String text, Set<FactualClaim> out) {
        Matcher m = INCOME_PATTERN.matcher(text);
        while (m.find()) {
            BigDecimal value = parseAmount(m.group(1));
            if (value == null) continue;
            out.add(new FactualClaim(
                "income_amount", "user", value, Map.of(),
                m.start(), m.end()));
        }
    }

    private static void addAnomalyCountClaims(String text, Set<FactualClaim> out) {
        Matcher m = ANOMALY_COUNT_PATTERN.matcher(text);
        while (m.find()) {
            try {
                BigDecimal count = new BigDecimal(m.group(1));
                out.add(new FactualClaim(
                    "anomaly_count", "user", count, Map.of(),
                    m.start(), m.end()));
            } catch (NumberFormatException ignored) {}
        }
    }

    private static void addGoalProgressClaims(String text, Set<FactualClaim> out) {
        Matcher m = GOAL_PROGRESS_PATTERN.matcher(text);
        while (m.find()) {
            BigDecimal current = parseAmount(m.group(1));
            BigDecimal target  = parseAmount(m.group(2));
            if (current == null || target == null) continue;
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("target_amount", target);
            out.add(new FactualClaim(
                "goal_progress", "user", current, attrs,
                m.start(), m.end()));
        }
    }

    private static void addMerchantClassClaims(String text, Set<FactualClaim> out) {
        Matcher m = MERCHANT_CLASS_PATTERN.matcher(text);
        while (m.find()) {
            String merchant = m.group(1) == null ? null : m.group(1).trim();
            String klass    = m.group(2) == null ? null : m.group(2).trim();
            if (merchant == null || merchant.isBlank()) continue;
            if (klass == null || klass.isBlank()) continue;
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("merchant_type", klass);
            out.add(new FactualClaim(
                "merchant_class", merchant, null, attrs,
                m.start(), m.end()));
        }
    }

    private static void addDateRangeClaims(String text, Set<FactualClaim> out) {
        Matcher num = DATE_RANGE_NUMERIC_PATTERN.matcher(text);
        while (num.find()) {
            int count;
            try { count = Integer.parseInt(num.group(1)); }
            catch (NumberFormatException ex) { continue; }
            String unit = num.group(2).toLowerCase();
            int windowDays = unitToDays(unit, count);
            if (windowDays <= 0) continue;
            out.add(buildDateRangeClaim(
                count, unit, windowDays, num.start(), num.end()));
        }

        Matcher bare = DATE_RANGE_BARE_PATTERN.matcher(text);
        while (bare.find()) {
            String unit = bare.group(1).toLowerCase();
            int windowDays = unitToDays(unit, 1);
            if (windowDays <= 0) continue;
            out.add(buildDateRangeClaim(
                1, unit, windowDays, bare.start(), bare.end()));
        }
    }

    private static FactualClaim buildDateRangeClaim(
            int count, String unit, int windowDays, int start, int end) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("count", count);
        attrs.put("unit", unit);
        attrs.put("window_days", windowDays);
        return new FactualClaim(
            "date_range", "user",
            new java.math.BigDecimal(windowDays),
            attrs, start, end);
    }

    /** Unit names mirror the spec; month/quarter/year use calendar averages. */
    private static int unitToDays(String unit, int count) {
        return switch (unit) {
            case "day"     -> count;
            case "week"    -> count * 7;
            case "month"   -> count * 30;
            case "quarter" -> count * 90;
            case "year"    -> count * 365;
            default        -> 0;
        };
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null) return null;
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
