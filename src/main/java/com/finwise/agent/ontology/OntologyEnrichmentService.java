package com.finwise.agent.ontology;

import com.finwise.agent.core.ReferenceDateProvider;
import com.finwise.agent.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OntologyEnrichmentService {

    private final ClassificationRuleRepository    classificationRuleRepository;
    private final SpendingCategoryRepository      spendingCategoryRepository;
    private final BudgetRepository                budgetRepository;
    private final TransactionEnrichmentRepository enrichmentRepository;
    private final SpendingCategoryFactory         categoryFactory; // ← separate bean
    private final ReferenceDateProvider           refDate;

    // Self-injected proxy so enrichAll can invoke enrich() through Spring's
    // @Transactional AOP (direct this.enrich() would bypass the proxy).
    // This isolates each per-transaction enrichment in its own REQUIRES_NEW
    // transaction so one failure does not taint the batch.
    @Autowired @Lazy
    private OntologyEnrichmentService self;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionEnrichment enrich(Transaction transaction) {
        return enrich(transaction, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionEnrichment enrich(Transaction transaction, boolean force) {
        if (!force && enrichmentRepository.existsByTransactionId(transaction.getId())) {
            log.debug("Transaction {} already enriched — skipping", transaction.getId());
            return enrichmentRepository
                .findByTransactionId(transaction.getId()).get();
        }
        if (force) {
            enrichmentRepository.findByTransactionId(transaction.getId())
                .ifPresent(existing -> {
                    enrichmentRepository.delete(existing);
                    // Flush so the DELETE hits the DB before the subsequent
                    // save() issues an INSERT that would collide with the
                    // UNIQUE constraint on transaction_id.
                    enrichmentRepository.flush();
                });
        }

        log.info("Enriching: {} | {} | {}",
            transaction.getId(),
            transaction.getMerchantName(),
            transaction.getAmount());

        TransactionEnrichment enrichment = new TransactionEnrichment();
        enrichment.setTransactionId(transaction.getId());

        // Step 1: Classify merchant
        ClassificationResult classification = classifyMerchant(
            transaction.getMerchantName(),
            transaction.getDescription()
        );

        if (classification.matched()) {
            enrichment.setMerchantProfile(classification.merchantProfile());
            enrichment.setCanonicalMerchantName(
                classification.merchantProfile().getCanonicalName());
            enrichment.setEnrichmentConfidence(classification.confidence());
        } else {
            enrichment.setCanonicalMerchantName(normalize(transaction.getMerchantName()));
            enrichment.setEnrichmentConfidence(BigDecimal.valueOf(0.30));
        }

        // Step 2: Resolve spending category (records which source produced the result)
        CategoryResolution resolution = resolveCategory(transaction, classification);
        if (resolution.category() != null) {
            enrichment.setSpendingCategory(resolution.category());
        }
        enrichment.setEnrichmentSource(resolution.source());

        SpendingCategory category = resolution.category();

        // Step 3: Budget utilization
        if (category != null && "DEBIT".equals(transaction.getTransactionType())) {
            computeBudgetUtilization(enrichment, transaction, category);
        }

        // Step 4: Anomaly detection
        detectAnomaly(enrichment, transaction, classification);

        TransactionEnrichment saved = enrichmentRepository.save(enrichment);
        log.info("Enriched {}: category={}, source={}, confidence={}, anomaly={}",
            transaction.getId(),
            category != null ? category.getName() : "none",
            resolution.source(),
            enrichment.getEnrichmentConfidence(),
            enrichment.getIsAnomaly());

        return saved;
    }

    public int enrichAll(List<Transaction> transactions) {
        return enrichAll(transactions, false);
    }

    public int enrichAll(List<Transaction> transactions, boolean force) {
        // Intentionally NOT @Transactional: each self.enrich() call opens its
        // own REQUIRES_NEW transaction via the Spring proxy, so a failure in
        // one transaction does not roll back the batch.
        int count = 0;
        for (Transaction tx : transactions) {
            try {
                self.enrich(tx, force);
                count++;
            } catch (Exception e) {
                log.error("Failed to enrich {}: {}", tx.getId(), e.getMessage());
            }
        }
        log.info("Enriched {}/{} (force={})", count, transactions.size(), force);
        return count;
    }

    // ── Classification ────────────────────────────────────────────

    private ClassificationResult classifyMerchant(
            String merchantName, String description) {

        if (merchantName == null && description == null) {
            return ClassificationResult.noMatch();
        }

        String searchName = normalize(
            merchantName != null ? merchantName : description);

        List<ClassificationRule> rules =
            classificationRuleRepository.findAllActiveOrderByPriority();

        for (ClassificationRule rule : rules) {
            if (matches(searchName, rule)) {
                return ClassificationResult.matched(
                    rule.getMerchantProfile(),
                    confidenceFor(rule.getPatternType())
                );
            }
        }
        return ClassificationResult.noMatch();
    }

    private boolean matches(String name, ClassificationRule rule) {
        String pattern = normalize(rule.getPattern());
        return switch (rule.getPatternType()) {
            case "EXACT"    -> name.equals(pattern);
            case "PREFIX"   -> name.startsWith(pattern);
            case "CONTAINS" -> name.contains(pattern);
            case "REGEX"    -> name.matches(pattern);
            default         -> name.contains(pattern);
        };
    }

    private BigDecimal confidenceFor(String patternType) {
        return switch (patternType) {
            case "EXACT"    -> BigDecimal.valueOf(0.99);
            case "PREFIX"   -> BigDecimal.valueOf(0.90);
            case "CONTAINS" -> BigDecimal.valueOf(0.85);
            default         -> BigDecimal.valueOf(0.70);
        };
    }

    // ── Category Resolution ───────────────────────────────────────
    //
    // Multi-source resolution, in priority order:
    //   1. Rule-based classification → merchant_type → user's spending_category
    //      (source: "RULES")
    //   2. Upstream canonical category from transaction metadata
    //      (source: "METADATA"; simulates bank-provided categorization)
    //   3. Type-based fallback — Income for CREDIT, Other for DEBIT
    //      (source: "FALLBACK")

    private record CategoryResolution(SpendingCategory category, String source) {}

    private CategoryResolution resolveCategory(
            Transaction transaction,
            ClassificationResult classification) {

        UUID userId = transaction.getUserId();
        String transactionType = transaction.getTransactionType();

        // 1. Rule-based classification
        if (classification.matched()) {
            MerchantProfile profile = classification.merchantProfile();
            MerchantType merchantType = profile.getMerchantType();
            if (merchantType != null) {
                return new CategoryResolution(
                    categoryFactory.findOrCreate(userId, merchantType), "RULES");
            }
        }

        // 2. Upstream canonical category from transaction metadata
        String metadataCategory = extractMetadataCategory(transaction);
        if (metadataCategory != null) {
            Optional<SpendingCategory> byMeta =
                spendingCategoryRepository.findByUserIdAndName(userId, metadataCategory);
            if (byMeta.isPresent()) {
                return new CategoryResolution(byMeta.get(), "METADATA");
            }
        }

        // 3. Type-based fallback
        String fallbackName = "CREDIT".equals(transactionType) ? "Income" : "Other";
        return new CategoryResolution(
            spendingCategoryRepository.findByUserIdAndName(userId, fallbackName).orElse(null),
            "FALLBACK");
    }

    private String extractMetadataCategory(Transaction transaction) {
        try {
            if (transaction.getMetadata() == null) return null;
            Object cat = transaction.getMetadata().get("category");
            if (cat == null) return null;
            String s = cat.toString().trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Budget Utilization ────────────────────────────────────────

    private void computeBudgetUtilization(
            TransactionEnrichment enrichment,
            Transaction transaction,
            SpendingCategory category) {

        LocalDate today = refDate.today();

        Optional<Budget> budgetOpt = budgetRepository.findActiveBudget(
            transaction.getUserId(), category.getId(), today);

        if (budgetOpt.isEmpty()) return;

        Budget budget = budgetOpt.get();
        enrichment.setBudgetId(budget.getId());

        Instant monthStart = today
            .with(TemporalAdjusters.firstDayOfMonth())
            .atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant monthEnd = today
            .with(TemporalAdjusters.lastDayOfMonth())
            .atTime(23, 59, 59).toInstant(ZoneOffset.UTC);

        BigDecimal spentSoFar = enrichmentRepository.sumSpentInCategory(
            transaction.getUserId(), category.getId(), monthStart, monthEnd);

        BigDecimal totalSpent = spentSoFar.add(transaction.getAmount());

        if (budget.getMonthlyLimit().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal utilization = totalSpent
                .divide(budget.getMonthlyLimit(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
            enrichment.setBudgetUtilizationPct(utilization);
        }
    }

    // ── Anomaly Detection ─────────────────────────────────────────

    private final StatisticalAnomalyDetector statisticalAnomalyDetector;
    private void detectAnomaly(
        TransactionEnrichment enrichment,
        Transaction transaction,
        ClassificationResult classification) {

    // Phase 4: statistical detection first (z-score based)
    String merchantName = classification.matched()
        ? classification.merchantProfile().getCanonicalName()
        : transaction.getMerchantName();

    StatisticalAnomalyDetector.AnomalyResult statistical =
        statisticalAnomalyDetector.detect(transaction, merchantName);

    if (statistical.isAnomaly()) {
        enrichment.setIsAnomaly(true);
        enrichment.setAnomalyReason(statistical.reason());
        return;
    }

    // Phase 2 fallback: threshold-based detection
    if (!classification.matched()) return;

    MerchantProfile profile = classification.merchantProfile();
    if (profile.getTypicalAmountMax() != null
            && transaction.getAmount() != null
            && transaction.getAmount().compareTo(
                profile.getTypicalAmountMax()
                    .multiply(BigDecimal.valueOf(3))) > 0) {

        enrichment.setIsAnomaly(true);
        enrichment.setAnomalyReason(String.format(
            "Amount $%.2f is more than 3x the typical max of $%.2f for %s",
            transaction.getAmount(),
            profile.getTypicalAmountMax(),
            profile.getCanonicalName()
        ));
    }
}

    // ── Utilities ─────────────────────────────────────────────────

    private String normalize(String input) {
        if (input == null) return "";
        return input.toUpperCase().trim();
    }

    // ── Inner record ──────────────────────────────────────────────

    record ClassificationResult(
        boolean matched,
        MerchantProfile merchantProfile,
        BigDecimal confidence
    ) {
        static ClassificationResult matched(
                MerchantProfile profile, BigDecimal confidence) {
            return new ClassificationResult(true, profile, confidence);
        }

        static ClassificationResult noMatch() {
            return new ClassificationResult(false, null, BigDecimal.ZERO);
        }
    }
}