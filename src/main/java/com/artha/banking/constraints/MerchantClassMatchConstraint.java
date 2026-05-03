package com.artha.banking.constraints;

import com.artha.banking.ontology.MerchantProfile;
import com.artha.banking.ontology.MerchantProfileRepository;
import com.artha.banking.ontology.MerchantType;
import com.artha.core.constraint.Constraint;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * SOFT claim-driven check: when the agent asserts that merchant X is a
 * {@code <type>}, the type must match {@link MerchantProfile#getMerchantType()}
 * (or one of its ancestors in the type hierarchy).
 *
 * Per research/ONTOLOGY_V2_SPEC.md §6.5. The extractor is liberal —
 * sentences like "Saving is a habit" also match {@code merchant_class}.
 * The constraint absorbs those false positives by silently skipping
 * any claim whose subject is not in {@link MerchantProfileRepository}
 * (canonical name or known variant). Only resolved claims are scored.
 *
 * Parent-chain walk is capped at depth 3 — enough for the v2 ontology
 * (Coffee → Food &amp; Dining → ...) and bounded against degenerate cycles.
 */
@Component
@RequiredArgsConstructor
public class MerchantClassMatchConstraint implements Constraint {

    private static final int MAX_PARENT_DEPTH = 3;

    private final MerchantProfileRepository merchantRepo;

    @Override public String name()              { return "MerchantClassMatch"; }
    @Override public String domain()            { return "banking"; }
    @Override public ConstraintGrade grade()    { return ConstraintGrade.SOFT; }
    @Override public String repairHintTemplate() {
        return "The merchant classification you stated does not match "
             + "what the merchant profile records. Re-check via "
             + "get_transactions or get_subscriptions and only assert "
             + "what the tool returns.";
    }

    @Override
    @Transactional(readOnly = true)
    public ConstraintResult evaluate(EvaluationContext ctx, Set<FactualClaim> claims) {
        for (FactualClaim claim : claims) {
            if (!"merchant_class".equals(claim.kind())) continue;
            String merchantName = claim.subject();
            Object claimedType  = claim.attrs().get("merchant_type");
            if (merchantName == null || merchantName.isBlank()) continue;
            if (!(claimedType instanceof String typeStr) || typeStr.isBlank()) continue;

            Optional<MerchantProfile> opt =
                merchantRepo.findByCanonicalName(merchantName);
            if (opt.isEmpty()) {
                opt = merchantRepo.findByNameVariant(merchantName);
            }
            if (opt.isEmpty()) continue;  // unresolved — silently skip (FP)

            MerchantProfile profile = opt.get();
            MerchantType type = profile.getMerchantType();
            if (type == null) continue;

            String claimNorm = typeStr.toLowerCase(Locale.ROOT).trim();
            if (!matchesTypeOrAncestor(type, claimNorm)) {
                String actual = type.getName() == null ? "<unnamed>" : type.getName();
                return new ConstraintResult.Violated(
                    "Agent claimed " + merchantName + " is '" + typeStr
                    + "' but the merchant profile records type '"
                    + actual + "'.",
                    repairHintTemplate());
            }
        }
        return ConstraintResult.Satisfied.INSTANCE;
    }

    private static boolean matchesTypeOrAncestor(MerchantType type, String claimNorm) {
        MerchantType cursor = type;
        for (int i = 0; i < MAX_PARENT_DEPTH && cursor != null; i++) {
            String name = cursor.getName();
            if (name != null
                && name.toLowerCase(Locale.ROOT).trim().equals(claimNorm)) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }
}
