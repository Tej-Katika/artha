package com.artha.banking.enrichment;

import com.artha.core.ReferenceDateProvider;
import com.artha.banking.ontology.*;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * GoalImpactEnrichmentService â€” Phase 7A
 *
 * For every transaction, determines which financial goals it helps or hurts
 * and by how much. This is the core of goal-transaction linkage.
 *
 * Impact rules:
 *   CREDIT from employer/payroll  â†’ helps ALL savings goals (income event)
 *   Transfer to Vanguard/Fidelity â†’ directly funds RETIREMENT goal
 *   Transfer to savings account   â†’ directly funds EMERGENCY_FUND goal
 *   Payday loan payment           â†’ hurts ALL goals (high-interest debt drain)
 *   Overdraft fee                 â†’ hurts ALL goals (cash leak)
 *   Dining/entertainment spend    â†’ hurts DISCRETIONARY budget goals
 *   Rent/mortgage payment         â†’ neutral (necessary fixed expense)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoalImpactEnrichmentService {

    private final FinancialGoalRepository         goalRepository;
    private final TransactionRepository          transactionRepository;
    private final TransactionEnrichmentRepository enrichmentRepository;
    private final ReferenceDateProvider           refDate;

    // â”€â”€ Merchant classification maps â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private static final Set<String> INVESTMENT_MERCHANTS = Set.of(
        "vanguard", "fidelity", "schwab", "betterment", "wealthfront",
        "robinhood", "e*trade", "etrade", "td ameritrade", "acorns"
    );

    private static final Set<String> SAVINGS_MERCHANTS = Set.of(
        "ally bank", "marcus", "sofi", "discover savings", "high yield savings",
        "capital one savings", "synchrony bank"
    );

    private static final Set<String> PAYDAY_LENDERS = Set.of(
        "ace cash express", "check into cash", "speedy cash", "advance america",
        "cashnetusa", "moneylion", "earnin", "dave"
    );

    private static final Set<String> DEBT_MERCHANTS = Set.of(
        "navient", "sallie mae", "mohela", "great lakes", "nelnet",
        "discover student", "sofi loan", "earnest"
    );

    private static final Set<String> FIXED_EXPENSE_CATEGORIES = Set.of(
        "rent", "mortgage", "insurance", "utilities", "phone", "internet"
    );

    // â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Analyze all transactions for a user over the last N days and
     * return a goal impact summary for each active goal.
     */
    public List<Map<String, Object>> analyzeGoalImpacts(UUID userId, int daysBack) {
        Instant to   = refDate.now();
        Instant from = to.minus(daysBack, ChronoUnit.DAYS);

        // Load transactions via enrichment (same pattern as other tools)
        List<TransactionEnrichment> enrichments =
            enrichmentRepository.findByUserIdAndDateRange(userId, from, to);
        List<Transaction> transactions = new ArrayList<>();
        for (TransactionEnrichment e : enrichments) {
            Optional<Transaction> txOpt = transactionRepository.findById(e.getTransactionId());
            txOpt.ifPresent(transactions::add);
        }
        List<FinancialGoal> activeGoals  = goalRepository.findByUserIdAndStatus(userId, "ACTIVE");

        if (activeGoals.isEmpty() || transactions.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> goalImpacts = new ArrayList<>();

        for (FinancialGoal goal : activeGoals) {
            GoalImpactAccumulator acc = new GoalImpactAccumulator(goal);

            for (Transaction tx : transactions) {
                TransactionImpact impact = classifyImpact(tx, goal);
                if (impact != TransactionImpact.NEUTRAL) {
                    acc.add(tx, impact);
                }
            }

            goalImpacts.add(acc.toMap());
        }

        return goalImpacts;
    }

    /**
     * Classify how a single transaction impacts a specific goal.
     */
    public TransactionImpact classifyImpact(Transaction tx, FinancialGoal goal) {
        String merchant  = tx.getMerchantName() != null
            ? tx.getMerchantName().toLowerCase() : "";
        String type      = tx.getTransactionType();
        BigDecimal amount = tx.getAmount();
        String goalType  = goal.getGoalType() != null
            ? goal.getGoalType().toUpperCase() : "";

        // â”€â”€ Direct positive impacts â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // Investment transfer â†’ directly funds retirement goal
        if ("DEBIT".equals(type) && isInvestmentMerchant(merchant)
                && goalType.contains("RETIREMENT")) {
            return TransactionImpact.DIRECTLY_FUNDS;
        }

        // Savings transfer â†’ directly funds emergency fund
        if ("DEBIT".equals(type) && isSavingsMerchant(merchant)
                && goalType.contains("EMERGENCY")) {
            return TransactionImpact.DIRECTLY_FUNDS;
        }

        // Payroll/income â†’ helps all savings goals
        if ("CREDIT".equals(type) && amount.compareTo(BigDecimal.valueOf(500)) > 0) {
            return TransactionImpact.INCOME_EVENT;
        }

        // â”€â”€ Direct negative impacts â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // Payday loan â†’ hurts ALL goals (debt trap)
        if ("DEBIT".equals(type) && isPaydayLender(merchant)) {
            return TransactionImpact.DEBT_DRAIN;
        }

        // Overdraft fee â†’ hurts ALL goals (preventable cash leak)
        if ("DEBIT".equals(type) && merchant.contains("overdraft")) {
            return TransactionImpact.CASH_LEAK;
        }

        // Student loan â†’ hurts savings goals but helps debt-payoff goal
        if ("DEBIT".equals(type) && isDebtMerchant(merchant)) {
            if (goalType.contains("DEBT")) {
                return TransactionImpact.DIRECTLY_FUNDS;
            }
            return TransactionImpact.DEBT_DRAIN;
        }

        // â”€â”€ Discretionary spending â†’ hurts savings goals â”€â”€â”€â”€â”€â”€â”€â”€â”€
        if ("DEBIT".equals(type) && !isFixedExpense(merchant)
                && amount.compareTo(BigDecimal.valueOf(50)) > 0
                && (goalType.contains("SAVINGS") || goalType.contains("EMERGENCY"))) {
            return TransactionImpact.DISCRETIONARY_SPEND;
        }

        return TransactionImpact.NEUTRAL;
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private boolean isInvestmentMerchant(String m) {
        return INVESTMENT_MERCHANTS.stream().anyMatch(m::contains);
    }

    private boolean isSavingsMerchant(String m) {
        return SAVINGS_MERCHANTS.stream().anyMatch(m::contains);
    }

    private boolean isPaydayLender(String m) {
        return PAYDAY_LENDERS.stream().anyMatch(m::contains);
    }

    private boolean isDebtMerchant(String m) {
        return DEBT_MERCHANTS.stream().anyMatch(m::contains);
    }

    private boolean isFixedExpense(String m) {
        return FIXED_EXPENSE_CATEGORIES.stream().anyMatch(m::contains);
    }

    // â”€â”€ Inner types â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    public enum TransactionImpact {
        DIRECTLY_FUNDS,      // transaction directly contributes to this goal
        INCOME_EVENT,        // income that could be saved toward this goal
        DEBT_DRAIN,          // high-interest debt eating into savings capacity
        CASH_LEAK,           // avoidable fees reducing savings capacity
        DISCRETIONARY_SPEND, // optional spending competing with goal
        NEUTRAL              // no meaningful impact on this goal
    }

    private static class GoalImpactAccumulator {
        private final FinancialGoal goal;
        private BigDecimal directFunds    = BigDecimal.ZERO;
        private BigDecimal incomeTotal    = BigDecimal.ZERO;
        private BigDecimal debtDrain      = BigDecimal.ZERO;
        private BigDecimal cashLeaks      = BigDecimal.ZERO;
        private BigDecimal discretionary  = BigDecimal.ZERO;
        private int        directCount    = 0;
        private int        debtCount      = 0;
        private int        leakCount      = 0;

        private final List<Map<String, Object>> topHelpers = new ArrayList<>();
        private final List<Map<String, Object>> topHurters = new ArrayList<>();

        GoalImpactAccumulator(FinancialGoal goal) {
            this.goal = goal;
        }

        void add(Transaction tx, TransactionImpact impact) {
            BigDecimal amt = tx.getAmount();
            String merchant = tx.getMerchantName() != null ? tx.getMerchantName() : "Unknown";

            switch (impact) {
                case DIRECTLY_FUNDS -> {
                    directFunds = directFunds.add(amt);
                    directCount++;
                    topHelpers.add(Map.of("merchant", merchant, "amount", amt, "type", "DIRECT_CONTRIBUTION"));
                }
                case INCOME_EVENT -> {
                    incomeTotal = incomeTotal.add(amt);
                }
                case DEBT_DRAIN -> {
                    debtDrain = debtDrain.add(amt);
                    debtCount++;
                    topHurters.add(Map.of("merchant", merchant, "amount", amt, "type", "DEBT_PAYMENT"));
                }
                case CASH_LEAK -> {
                    cashLeaks = cashLeaks.add(amt);
                    leakCount++;
                    topHurters.add(Map.of("merchant", merchant, "amount", amt, "type", "FEE"));
                }
                case DISCRETIONARY_SPEND -> {
                    discretionary = discretionary.add(amt);
                }
                default -> {}
            }
        }

        Map<String, Object> toMap() {
            BigDecimal totalPositive = directFunds.add(incomeTotal);
            BigDecimal totalNegative = debtDrain.add(cashLeaks);
            BigDecimal netImpact     = totalPositive.subtract(totalNegative).subtract(discretionary);

            String assessment;
            if (debtDrain.compareTo(BigDecimal.valueOf(200)) > 0) {
                assessment = "DEBT_CONSTRAINED";
            } else if (cashLeaks.compareTo(BigDecimal.valueOf(50)) > 0) {
                assessment = "LEAKING";
            } else if (directFunds.compareTo(BigDecimal.ZERO) > 0) {
                assessment = "ON_TRACK";
            } else if (incomeTotal.compareTo(BigDecimal.ZERO) > 0) {
                assessment = "INCOME_DEPENDENT";
            } else {
                assessment = "STALLED";
            }

            // Sort hurters by amount desc, take top 3
            topHurters.sort((a, b) ->
                ((BigDecimal) b.get("amount")).compareTo((BigDecimal) a.get("amount")));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("goal_name",         goal.getName());
            result.put("goal_type",         goal.getGoalType());
            result.put("assessment",        assessment);
            result.put("net_impact",        netImpact);
            result.put("direct_funds",      directFunds);
            result.put("income_total",      incomeTotal);
            result.put("debt_drain",        debtDrain);
            result.put("cash_leaks",        cashLeaks);
            result.put("discretionary",     discretionary);
            result.put("direct_contributions", directCount);
            result.put("debt_payments",     debtCount);
            result.put("fee_events",        leakCount);
            result.put("top_helpers",       topHelpers.stream().limit(3).toList());
            result.put("top_hurters",       topHurters.stream().limit(3).toList());
            return result;
        }
    }
}