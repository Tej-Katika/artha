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
 * as a JSON tool-call alongside the prose. This implementation is
 * deliberately narrow — pulls out the four claim shapes that exercise
 * the constraint axis end-to-end:
 *
 * <ul>
 *   <li>{@code spending_amount} — "$X on Y" or "spent $X"</li>
 *   <li>{@code income_amount}   — "earned $X" or "received $X"</li>
 *   <li>{@code anomaly_count}   — "N anomalies" / "N flagged transactions"</li>
 *   <li>{@code goal_progress}   — "$X toward your $Y goal"</li>
 * </ul>
 *
 * The patterns intentionally over-match (favour recall over precision)
 * — false positives are absorbed by the constraints themselves
 * (a bogus claim either trips the check or harmlessly resolves).
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

    @Override
    public Set<FactualClaim> extract(String responseText, String domain) {
        Set<FactualClaim> claims = new HashSet<>();
        if (responseText == null || responseText.isBlank()) return claims;
        if (!"banking".equals(domain)) return claims;

        addSpendingClaims(responseText, claims);
        addIncomeClaims(responseText, claims);
        addAnomalyCountClaims(responseText, claims);
        addGoalProgressClaims(responseText, claims);

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

    private static BigDecimal parseAmount(String raw) {
        if (raw == null) return null;
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
