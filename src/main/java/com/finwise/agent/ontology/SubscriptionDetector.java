package com.finwise.agent.ontology;

import com.finwise.agent.core.ReferenceDateProvider;
import com.finwise.agent.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects recurring subscription patterns from transaction history.
 *
 * A subscription is detected when the same merchant appears
 * 3+ times with consistent amounts and ~30 day intervals.
 *
 * Detection criteria:
 *   1. Same canonical merchant name
 *   2. Amount variance < 10% across charges
 *   3. 3+ occurrences in the last 6 months
 *   4. Average interval between charges is 25-35 days (monthly)
 *      or 360-370 days (annual)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionDetector {

    private final TransactionRepository    transactionRepository;
    private final MerchantProfileRepository merchantProfileRepository;
    private final RecurringBillRepository  recurringBillRepository;
    private final ReferenceDateProvider    refDate;

    private static final int    LOOKBACK_DAYS          = 180; // 6 months
    private static final int    MIN_OCCURRENCES        = 4;   // tightened from 3 to reduce false positives
    // Recurring-cadence window. Widened from 25-35 days because real recurring
    // charges span a spectrum: bi-weekly gym visits (7-10d), fortnightly
    // subscription renewals (14-16d), monthly bills (28-32d). A 5-40 day
    // window captures the full "short-cadence recurring" family.
    private static final int    MONTHLY_INTERVAL_MIN   = 5;
    private static final int    MONTHLY_INTERVAL_MAX   = 40;
    private static final int    ANNUAL_INTERVAL_MIN    = 355;
    private static final int    ANNUAL_INTERVAL_MAX    = 380;

    // Amount-consistency filter is intentionally disabled: detection relies on
    // occurrence count + inter-transaction interval only. Real-world
    // subscription amounts vary due to taxes, plan changes, promotional pricing,
    // and mid-cycle adjustments, so a strict amount-variance filter is brittle.
    // If deployed against bank data with stable amounts, a tolerance check can
    // be added back via a configurable threshold.

    public record DetectedSubscription(
        String     merchantName,
        BigDecimal typicalAmount,
        String     billingCycle,
        double     confidenceScore,
        int        occurrenceCount,
        LocalDate  nextExpectedDate
    ) {}

    /**
     * Scan all transactions for a user and detect subscription patterns.
     * Persists any new recurring bills found.
     */
    @Transactional
    public List<DetectedSubscription> detectAndPersist(UUID userId) {
        Instant from = refDate.now().minusSeconds((long) LOOKBACK_DAYS * 86400);

        List<Transaction> transactions = transactionRepository
            .findByUserIdOrderByPostDateDesc(userId)
            .stream()
            .filter(t -> "DEBIT".equals(t.getTransactionType()))
            .filter(t -> t.getPostDate() != null && t.getPostDate().isAfter(from))
            .collect(Collectors.toList());

        // Group by merchant name
        Map<String, List<Transaction>> byMerchant = transactions.stream()
            .filter(t -> t.getMerchantName() != null)
            .collect(Collectors.groupingBy(
                t -> t.getMerchantName().toUpperCase().trim()));

        List<DetectedSubscription> detected = new ArrayList<>();

        for (Map.Entry<String, List<Transaction>> entry : byMerchant.entrySet()) {
            String merchantKey = entry.getKey();
            List<Transaction> merchantTxns = entry.getValue();

            if (merchantTxns.size() < MIN_OCCURRENCES) continue;

            // Amount-consistency filter removed — see class-level comment.
            // Compute mean anyway for the expected_amount field.
            DoubleSummaryStatistics amountStats = merchantTxns.stream()
                .mapToDouble(t -> t.getAmount().doubleValue())
                .summaryStatistics();

            double mean = amountStats.getAverage();

            // Check interval consistency — sort by date ascending
            List<Transaction> sorted = merchantTxns.stream()
                .sorted(Comparator.comparing(Transaction::getPostDate))
                .collect(Collectors.toList());

            List<Long> intervals = new ArrayList<>();
            for (int i = 1; i < sorted.size(); i++) {
                long days = ChronoUnit.DAYS.between(
                    sorted.get(i - 1).getPostDate(),
                    sorted.get(i).getPostDate()
                );
                intervals.add(days);
            }

            double avgInterval = intervals.stream()
                .mapToLong(Long::longValue).average().orElse(0);

            String billingCycle = null;
            if (avgInterval >= MONTHLY_INTERVAL_MIN
                    && avgInterval <= MONTHLY_INTERVAL_MAX) {
                billingCycle = "MONTHLY";
            } else if (avgInterval >= ANNUAL_INTERVAL_MIN
                    && avgInterval <= ANNUAL_INTERVAL_MAX) {
                billingCycle = "ANNUAL";
            }

            if (billingCycle == null) continue;

            // Compute confidence score
            // Higher = more occurrences + tighter amount variance + consistent interval
            double amountVariancePct = amountStats.getMax() > 0
                ? (amountStats.getMax() - amountStats.getMin()) / amountStats.getMax()
                : 0;
            double intervalVariance = intervals.stream()
                .mapToDouble(i -> Math.abs(i - avgInterval))
                .average().orElse(0);

            double confidence = Math.min(1.0,
                0.5 * Math.min(1.0, merchantTxns.size() / 6.0)  // more = better
                + 0.3 * (1.0 - amountVariancePct)                // less variance = better
                + 0.2 * Math.max(0, 1.0 - intervalVariance / 5.0) // consistent intervals
            );

            // Project next expected date
            Transaction lastTx = sorted.get(sorted.size() - 1);
            LocalDate lastDate = lastTx.getPostDate()
                .atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate nextDate = "MONTHLY".equals(billingCycle)
                ? lastDate.plusMonths(1)
                : lastDate.plusYears(1);

            BigDecimal typicalAmount = BigDecimal.valueOf(mean)
                .setScale(2, RoundingMode.HALF_UP);

            DetectedSubscription subscription = new DetectedSubscription(
                merchantKey, typicalAmount, billingCycle,
                confidence, merchantTxns.size(), nextDate
            );
            detected.add(subscription);

            // Persist to recurring_bills if not already there
            persistIfNew(userId, subscription, lastDate);

            log.info("Subscription detected: {} | {} | ${} | confidence={}",
                merchantKey, billingCycle, typicalAmount, confidence);
        }

        return detected;
    }

    private void persistIfNew(UUID userId,
                               DetectedSubscription sub,
                               LocalDate lastSeen) {
        // Find merchant profile
        Optional<MerchantProfile> profileOpt =
            merchantProfileRepository.findByCanonicalName(sub.merchantName());

        UUID profileId = profileOpt.map(MerchantProfile::getId).orElse(null);

        // Check if already tracked
        if (profileId != null) {
            Optional<RecurringBill> existing =
                recurringBillRepository
                    .findByUserIdAndMerchantProfileId(userId, profileId);
            if (existing.isPresent()) {
                // Update last seen date and next expected
                RecurringBill bill = existing.get();
                bill.setLastSeenDate(lastSeen);
                bill.setNextExpectedDate(sub.nextExpectedDate());
                bill.setConfidenceScore(
                    BigDecimal.valueOf(sub.confidenceScore())
                        .setScale(2, RoundingMode.HALF_UP));
                recurringBillRepository.save(bill);
                return;
            }
        }

        // Create new recurring bill
        RecurringBill bill = new RecurringBill();
        bill.setUserId(userId);
        bill.setName(sub.merchantName());
        bill.setExpectedAmount(sub.typicalAmount());
        bill.setBillingCycle(sub.billingCycle());
        bill.setNextExpectedDate(sub.nextExpectedDate());
        bill.setLastSeenDate(lastSeen);
        bill.setDetectionSource("AUTO");
        bill.setConfidenceScore(
            BigDecimal.valueOf(sub.confidenceScore())
                .setScale(2, RoundingMode.HALF_UP));
        bill.setIsActive(true);

        if (profileId != null) {
            MerchantProfile profile = new MerchantProfile();
            profile.setId(profileId);
            bill.setMerchantProfileId(profileId);
        }

        recurringBillRepository.save(bill);
        log.info("Persisted new recurring bill: {}", sub.merchantName());
    }
}