package com.artha.banking.constraints;

import com.artha.core.constraint.Constraint;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * SOFT claim-driven check: when the agent uses a "last N days/weeks/
 * months/quarters/years" phrase, any explicit ISO-8601 date in the
 * response must fall within {@code [referenceDate - windowDays,
 * referenceDate + 1 day tolerance]}.
 *
 * Per research/ONTOLOGY_V2_SPEC.md §6.5. The constraint catches the
 * common hallucination shape where the agent says "in the last 30
 * days" but cites transactions from much earlier. Two layers of
 * checks:
 *
 * <ol>
 *   <li>Sanity: any window outside {@code [1, 3650]} days is implausible
 *       on its face — the agent fabricated a nonsensical period.</li>
 *   <li>Cross-reference: ISO dates in the response text must fall
 *       within the largest claimed window.</li>
 * </ol>
 *
 * If multiple {@code date_range} claims appear with differing windows,
 * we use the largest (most permissive) — only obviously-out-of-window
 * dates trip the check.
 */
@Component
public class DateRangeBoundingConstraint implements Constraint {

    private static final int WINDOW_FLOOR_DAYS  = 1;
    private static final int WINDOW_CEILING_DAYS = 3650;   // 10 years
    private static final int FORWARD_TOLERANCE_DAYS = 1;

    private static final Pattern ISO_DATE_PATTERN = Pattern.compile(
        "\\b(20[0-9]{2})-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])\\b");

    @Override public String name()              { return "DateRangeBounding"; }
    @Override public String domain()            { return "banking"; }
    @Override public ConstraintGrade grade()    { return ConstraintGrade.SOFT; }
    @Override public String repairHintTemplate() {
        return "The date range you cited does not align with the "
             + "transactions or facts you referenced. Recompute the "
             + "window relative to the current reference date and "
             + "either fix the phrasing or only cite dates that fall "
             + "inside the window.";
    }

    @Override
    public ConstraintResult evaluate(EvaluationContext ctx, Set<FactualClaim> claims) {
        List<FactualClaim> dateRangeClaims = claims.stream()
            .filter(c -> "date_range".equals(c.kind()))
            .toList();
        if (dateRangeClaims.isEmpty()) {
            return ConstraintResult.Satisfied.INSTANCE;
        }

        // 1) Sanity check — implausible windows.
        int largestWindow = 0;
        for (FactualClaim c : dateRangeClaims) {
            Integer windowDays = readWindowDays(c);
            if (windowDays == null) continue;
            if (windowDays < WINDOW_FLOOR_DAYS || windowDays > WINDOW_CEILING_DAYS) {
                return new ConstraintResult.Violated(
                    "Claimed date range of " + windowDays
                    + " days is implausible (outside "
                    + WINDOW_FLOOR_DAYS + "–" + WINDOW_CEILING_DAYS + ").",
                    repairHintTemplate());
            }
            if (windowDays > largestWindow) largestWindow = windowDays;
        }
        if (largestWindow == 0) {
            return ConstraintResult.Satisfied.INSTANCE;
        }

        // 2) Cross-reference — any ISO dates in the response must fit
        //    inside the largest claimed window.
        LocalDate ref = LocalDate.ofInstant(ctx.referenceDate(), ZoneOffset.UTC);
        LocalDate earliest = ref.minusDays(largestWindow);
        LocalDate latest   = ref.plusDays(FORWARD_TOLERANCE_DAYS);

        Matcher m = ISO_DATE_PATTERN.matcher(ctx.responseText());
        while (m.find()) {
            LocalDate d;
            try { d = LocalDate.parse(m.group()); }
            catch (DateTimeParseException ex) { continue; }

            if (d.isBefore(earliest) || d.isAfter(latest)) {
                return new ConstraintResult.Violated(
                    "Date " + d + " in the response is outside the "
                    + "claimed window of " + largestWindow + " days "
                    + "from reference " + ref + ".",
                    repairHintTemplate());
            }
        }
        return ConstraintResult.Satisfied.INSTANCE;
    }

    private static Integer readWindowDays(FactualClaim c) {
        Object v = c.attrs().get("window_days");
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        return c.value() == null ? null : c.value().intValueExact();
    }
}
