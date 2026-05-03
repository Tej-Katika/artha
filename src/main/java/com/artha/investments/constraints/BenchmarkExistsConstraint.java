package com.artha.investments.constraints;

import com.artha.core.constraint.Constraint;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import com.artha.investments.ontology.BenchmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * SOFT claim-driven check: any {@code benchmark_name} claim
 * (e.g. "compared to SPY", "vs the AGG") must resolve to a row in
 * {@code benchmarks}. Catches comparisons against benchmarks that
 * aren't in the system's reference set.
 *
 * Indeterminate (no violation) when no benchmark claim was
 * extracted; the constraint only fires when a claim is present and
 * has no supporting row.
 */
@Component
@RequiredArgsConstructor
public class BenchmarkExistsConstraint implements Constraint {

    private final BenchmarkRepository benchmarkRepo;

    @Override public String name()              { return "BenchmarkExists"; }
    @Override public String domain()            { return "investments"; }
    @Override public ConstraintGrade grade()    { return ConstraintGrade.SOFT; }
    @Override public String repairHintTemplate() {
        return "The benchmark you referenced is not in the system's "
             + "registered benchmarks. Use one of the supported "
             + "benchmarks (SPY, AGG, IEFA, BITO, XLK, ICLN) or remove "
             + "the comparison.";
    }

    @Override
    @Transactional(readOnly = true)
    public ConstraintResult evaluate(EvaluationContext ctx, Set<FactualClaim> claims) {
        for (FactualClaim claim : claims) {
            if (!"benchmark_name".equals(claim.kind())) continue;
            String ticker = claim.subject();
            if (ticker == null || ticker.isBlank()) continue;
            if (benchmarkRepo.findByTicker(ticker.trim().toUpperCase()).isEmpty()) {
                return new ConstraintResult.Violated(
                    "Benchmark '" + ticker + "' is not registered in benchmarks",
                    repairHintTemplate());
            }
        }
        return ConstraintResult.Satisfied.INSTANCE;
    }
}
