package com.artha.banking.constraints;

import com.artha.core.constraint.FactualClaim;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link RegexClaimExtractor} — no Spring context.
 *
 * The patterns favour recall over precision; the tests verify that
 * the four target claim shapes are each captured at least once on
 * representative agent prose.
 */
class RegexClaimExtractorTest {

    private final RegexClaimExtractor extractor = new RegexClaimExtractor();

    @Test
    void extractsSpendingClaim() {
        Set<FactualClaim> claims = extractor.extract(
            "You spent $1,234.56 on groceries last month.", "banking");

        assertThat(claims)
            .extracting(FactualClaim::kind, FactualClaim::value)
            .contains(tupleOf("spending_amount", new BigDecimal("1234.56")));
    }

    @Test
    void extractsCategoryAttribute() {
        Set<FactualClaim> claims = extractor.extract(
            "Paid $89.00 at Whole Foods for groceries.", "banking");

        // Two spending_amount entries are expected — the verb-prefixed
        // one ("Paid $89.00") and the category-context one
        // ("$89.00 at Whole Foods …"). At least one carries a category.
        FactualClaim withCategory = claims.stream()
            .filter(x -> "spending_amount".equals(x.kind()))
            .filter(x -> x.attrs().containsKey("category"))
            .findFirst()
            .orElseThrow();

        assertThat(withCategory.attrs().get("category")).isNotNull();
    }

    @Test
    void extractsIncomeClaim() {
        Set<FactualClaim> claims = extractor.extract(
            "Your last paycheck deposited $4,250.00 on the 15th.",
            "banking");

        assertThat(claims)
            .extracting(FactualClaim::kind, FactualClaim::value)
            .contains(tupleOf("income_amount", new BigDecimal("4250.00")));
    }

    @Test
    void extractsAnomalyCount() {
        Set<FactualClaim> claims = extractor.extract(
            "I found 3 anomalies in your recent transactions.", "banking");

        assertThat(claims)
            .extracting(FactualClaim::kind, FactualClaim::value)
            .contains(tupleOf("anomaly_count", new BigDecimal("3")));
    }

    @Test
    void extractsGoalProgress() {
        Set<FactualClaim> claims = extractor.extract(
            "You have $2,500 saved toward your $10,000 emergency fund goal.",
            "banking");

        FactualClaim c = claims.stream()
            .filter(x -> "goal_progress".equals(x.kind()))
            .findFirst()
            .orElseThrow();

        assertThat(c.value()).isEqualByComparingTo("2500");
        assertThat((BigDecimal) c.attrs().get("target_amount"))
            .isEqualByComparingTo("10000");
    }

    @Test
    void returnsEmptyForBlankResponse() {
        assertThat(extractor.extract("", "banking")).isEmpty();
        assertThat(extractor.extract(null, "banking")).isEmpty();
    }

    @Test
    void returnsEmptyForUnknownDomain() {
        assertThat(extractor.extract(
            "You spent $100 on groceries.", "investments")).isEmpty();
    }

    /** Helper to construct an AssertJ tuple inline without static import noise. */
    private static org.assertj.core.groups.Tuple tupleOf(Object... vals) {
        return new org.assertj.core.groups.Tuple(vals);
    }

    /** Sanity check: extracting from a long compound response captures multiple claims. */
    @Test
    void extractsMultipleClaimsFromCompoundResponse() {
        String response = """
            Your monthly summary: you spent $1,840.50 last month, mostly on
            groceries and dining. You earned $5,200.00 in the same period.
            Across that window I flagged 2 anomalies.
            """;

        Set<String> kinds = extractor.extract(response, "banking").stream()
            .map(FactualClaim::kind)
            .collect(Collectors.toSet());

        assertThat(kinds).contains(
            "spending_amount", "income_amount", "anomaly_count");
    }
}
