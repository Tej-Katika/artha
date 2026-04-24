package com.artha.agent.ontology;

import com.artha.agent.core.ReferenceDateProvider;
import com.artha.agent.domain.Transaction;
import com.artha.agent.domain.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Z-score based statistical anomaly detection.
 *
 * A transaction is anomalous if its amount is more than
 * ANOMALY_THRESHOLD standard deviations above the mean
 * for that merchant over the past LOOKBACK_DAYS days.
 *
 * Example:
 *   Netflix history: [$15.99, $15.99, $15.99, $15.99]
 *   Mean = $15.99, StdDev ≈ 0
 *   New charge: $89.99
 *   Z-score = (89.99 - 15.99) / 0.01 = very high → ANOMALY
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatisticalAnomalyDetector {

    private final TransactionRepository transactionRepository;
    private final ReferenceDateProvider refDate;

    private static final double Z_SCORE_THRESHOLD = 2.5;  // flag if >2.5 std devs above mean
    private static final int    LOOKBACK_DAYS      = 30;   // history window (30-day rolling baseline)
    private static final int    MIN_SAMPLE_SIZE    = 3;    // need at least 3 prior transactions

    public record AnomalyResult(
        boolean isAnomaly,
        String  reason,
        double  zScore,
        BigDecimal historicalMean,
        BigDecimal historicalStdDev,
        int     sampleSize
    ) {
        static AnomalyResult noAnomaly() {
            return new AnomalyResult(false, null, 0, null, null, 0);
        }

        static AnomalyResult insufficientData() {
            return new AnomalyResult(false, "insufficient_history", 0, null, null, 0);
        }
    }

    /**
     * Detect if a transaction amount is statistically anomalous
     * for the given merchant and user.
     */
    public AnomalyResult detect(Transaction transaction, String merchantName) {
        if (merchantName == null || transaction.getAmount() == null) {
            return AnomalyResult.noAnomaly();
        }

        UUID   userId = transaction.getUserId();
        Instant from  = refDate.now().minusSeconds((long) LOOKBACK_DAYS * 86400);
        Instant to    = transaction.getPostDate() != null
            ? transaction.getPostDate() : refDate.now();

        // Get historical transactions for this merchant
        List<Transaction> history = transactionRepository
            .findByUserIdOrderByPostDateDesc(userId)
            .stream()
            .filter(t -> !t.getId().equals(transaction.getId()))
            .filter(t -> t.getPostDate() != null && t.getPostDate().isAfter(from)
                         && t.getPostDate().isBefore(to))
            .filter(t -> merchantName.equalsIgnoreCase(t.getMerchantName())
                         || (t.getDescription() != null
                             && t.getDescription().toUpperCase()
                                .contains(merchantName.toUpperCase())))
            .filter(t -> "DEBIT".equals(t.getTransactionType()))
            .collect(Collectors.toList());

        if (history.size() < MIN_SAMPLE_SIZE) {
            log.debug("Insufficient history for {}: {} transactions", merchantName, history.size());
            return AnomalyResult.insufficientData();
        }

        // Compute mean
        double sum = history.stream()
            .mapToDouble(t -> t.getAmount().doubleValue())
            .sum();
        double mean = sum / history.size();

        // Compute standard deviation
        double variance = history.stream()
            .mapToDouble(t -> {
                double diff = t.getAmount().doubleValue() - mean;
                return diff * diff;
            })
            .sum() / history.size();
        double stdDev = Math.sqrt(variance);

        // Avoid division by zero for consistent charges (e.g. Netflix always $15.99)
        if (stdDev < 0.01) stdDev = 0.01;

        double currentAmount = transaction.getAmount().doubleValue();
        double zScore = (currentAmount - mean) / stdDev;

        log.debug("Z-score for {} at ${}: mean=${}, stdDev={}, z={}",
            merchantName, currentAmount, mean, stdDev, zScore);

        if (zScore > Z_SCORE_THRESHOLD) {
            String reason = String.format(
                "Amount $%.2f is %.1f standard deviations above your usual spend " +
                "of $%.2f at %s (based on %d prior transactions)",
                currentAmount, zScore, mean, merchantName, history.size()
            );

            return new AnomalyResult(
                true, reason, zScore,
                BigDecimal.valueOf(mean).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(stdDev).setScale(2, RoundingMode.HALF_UP),
                history.size()
            );
        }

        return AnomalyResult.noAnomaly();
    }
}