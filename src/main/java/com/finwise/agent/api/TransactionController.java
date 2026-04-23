package com.finwise.agent.api;

import com.finwise.agent.domain.Transaction;
import com.finwise.agent.domain.TransactionRepository;
import com.finwise.agent.ontology.OntologyEnrichmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionRepository     transactionRepository;
    private final OntologyEnrichmentService enrichmentService;

    // ── Add a transaction ─────────────────────────────────────────
    @PostMapping
    public ResponseEntity<?> addTransaction(@RequestBody AddTransactionRequest req) {
        if (req.referenceId() != null &&
            transactionRepository.existsByReferenceId(req.referenceId())) {
            return ResponseEntity.badRequest()
                .body("Duplicate: reference_id already exists");
        }

        Transaction t = new Transaction();
        t.setUserId(req.userId());
        t.setBankAccountId(req.bankAccountId());
        t.setTransactionType(req.transactionType());
        t.setPostDate(req.postDate());
        t.setDescription(req.description());
        t.setMerchantName(req.merchantName());
        t.setAmount(req.amount());
        t.setBalance(req.balance());
        t.setPaymentMethod(req.paymentMethod());
        t.setReferenceId(req.referenceId());

        Transaction saved = transactionRepository.save(t);
        log.info("Transaction created: {} | merchant: {} | amount: {}",
            saved.getId(), saved.getMerchantName(), saved.getAmount());

        // Auto-enrich with ontology layer
        try {
            enrichmentService.enrich(saved);
            log.info("Transaction {} enriched successfully", saved.getId());
        } catch (Exception e) {
            // Enrichment failure must never block transaction creation
            log.warn("Enrichment failed for transaction {} — continuing: {}",
                saved.getId(), e.getMessage());
        }

        return ResponseEntity.ok(saved);
    }

    // ── List transactions for a user ──────────────────────────────
    @GetMapping
    public ResponseEntity<List<Transaction>> listTransactions(
            @RequestParam UUID userId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {

        if (from != null && to != null) {
            return ResponseEntity.ok(
                transactionRepository
                    .findByUserIdAndPostDateBetweenOrderByPostDateDesc(userId, from, to)
            );
        }
        return ResponseEntity.ok(
            transactionRepository.findByUserIdOrderByPostDateDesc(userId)
        );
    }

    // ── Spending summary ──────────────────────────────────────────
    @GetMapping("/summary")
    public ResponseEntity<SpendingSummary> getSummary(
            @RequestParam UUID userId,
            @RequestParam Instant from,
            @RequestParam Instant to) {

        BigDecimal spent    = transactionRepository.totalSpent(userId, from, to);
        BigDecimal received = transactionRepository.totalReceived(userId, from, to);

        return ResponseEntity.ok(new SpendingSummary(
            userId, from, to,
            spent,
            received,
            received.subtract(spent)
        ));
    }

    // ── DTOs ──────────────────────────────────────────────────────

    public record AddTransactionRequest(
        UUID       userId,
        UUID       bankAccountId,
        String     transactionType,
        Instant    postDate,
        String     description,
        String     merchantName,
        BigDecimal amount,
        BigDecimal balance,
        String     paymentMethod,
        String     referenceId
    ) {}

    public record SpendingSummary(
        UUID       userId,
        Instant    from,
        Instant    to,
        BigDecimal totalSpent,
        BigDecimal totalReceived,
        BigDecimal netFlow
    ) {}
}