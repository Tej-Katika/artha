package com.artha.api;

import com.artha.banking.ontology.*;
import com.artha.banking.enrichment.OntologyEnrichmentService;
import com.artha.banking.enrichment.SubscriptionDetector;
import com.artha.banking.enrichment.SubscriptionDetector.DetectedSubscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/enrichment")
@RequiredArgsConstructor
public class EnrichmentController {

    private final OntologyEnrichmentService       enrichmentService;
    private final TransactionRepository           transactionRepository;
    private final TransactionEnrichmentRepository enrichmentRepository;
    private final SubscriptionDetector            subscriptionDetector;
    private final RecurringBillRepository         recurringBillRepository;

    @PostMapping("/transaction/{transactionId}")
    public ResponseEntity<Map<String, Object>> enrichTransaction(
            @PathVariable UUID transactionId) {

        return transactionRepository.findById(transactionId)
            .map(tx -> {
                TransactionEnrichment enrichment = enrichmentService.enrich(tx);
                return ResponseEntity.ok(enrichmentToMap(enrichment));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/user/{userId}/all")
    public ResponseEntity<Map<String, Object>> enrichAll(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "false") boolean force) {

        List<Transaction> transactions =
            transactionRepository.findByUserIdOrderByPostDateDesc(userId);

        int enriched = enrichmentService.enrichAll(transactions, force);

        return ResponseEntity.ok(Map.of(
            "userId",   userId,
            "total",    transactions.size(),
            "enriched", enriched,
            "force",    force
        ));
    }

    @PostMapping("/user/{userId}/subscriptions")
    @Transactional
    public ResponseEntity<Map<String, Object>> detectSubscriptions(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "false") boolean force) {

        if (force) {
            long removed = recurringBillRepository.deleteByUserId(userId);
            log.info("Cleared {} existing recurring_bills for user {}", removed, userId);
        }

        List<DetectedSubscription> detected =
            subscriptionDetector.detectAndPersist(userId);

        return ResponseEntity.ok(Map.of(
            "userId",         userId,
            "detected_count", detected.size(),
            "force",          force,
            "subscriptions",  detected
        ));
    }

    @Transactional(readOnly = true)
    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<Map<String, Object>> getEnrichment(
            @PathVariable UUID transactionId) {

        return enrichmentRepository.findByTransactionId(transactionId)
            .map(e -> ResponseEntity.ok(enrichmentToMap(e)))
            .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> enrichmentToMap(TransactionEnrichment e) {
        // Safely resolve lazy-loaded associations
        String categoryName = "Uncategorized";
        try {
            if (e.getSpendingCategory() != null) {
                categoryName = e.getSpendingCategory().getName();
            }
        } catch (Exception ex) {
            log.debug("Could not load spending category: {}", ex.getMessage());
        }

        String merchantName = "";
        try {
            if (e.getCanonicalMerchantName() != null) {
                merchantName = e.getCanonicalMerchantName();
            } else if (e.getMerchantProfile() != null) {
                merchantName = e.getMerchantProfile().getCanonicalName();
            }
        } catch (Exception ex) {
            log.debug("Could not load merchant profile: {}", ex.getMessage());
        }

        return Map.of(
            "transactionId",        e.getTransactionId(),
            "canonicalMerchant",    merchantName,
            "category",             categoryName,
            "confidence",           e.getEnrichmentConfidence() != null
                                        ? e.getEnrichmentConfidence() : 0,
            "budgetUtilizationPct", e.getBudgetUtilizationPct() != null
                                        ? e.getBudgetUtilizationPct() : 0,
            "isAnomaly",            e.getIsAnomaly() != null
                                        ? e.getIsAnomaly() : false,
            "anomalyReason",        e.getAnomalyReason() != null
                                        ? e.getAnomalyReason() : "",
            "enrichmentSource",     e.getEnrichmentSource() != null
                                        ? e.getEnrichmentSource() : "RULES"
        );
    }
}