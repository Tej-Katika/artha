package com.artha.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.artha.agent.core.ArthaTool;
import com.artha.agent.core.FinancialTool;
import com.artha.agent.core.ToolContext;
import com.artha.agent.core.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Tool: get_financial_education — Phase 8A
 *
 * Explains any financial term, concept, or product in plain language,
 * personalized to the user's actual financial situation.
 *
 * Examples:
 *   "What is a Roth IRA?"
 *   "Explain compound interest"
 *   "What's the difference between a debit and credit card?"
 *   "What is APR and why does it matter for my payday loan?"
 */
@Slf4j
@ArthaTool(
    description = "Explain any financial concept, term, or product in plain language",
    category    = "education",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetFinancialEducationTool implements FinancialTool {

    @Override
    public String getName() { return "get_financial_education"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Explains financial terms, concepts, products, and strategies in
                plain language tailored to the user's situation.
                Covers: savings accounts, investment vehicles (401k, IRA, index funds),
                debt concepts (APR, compound interest, credit score), budgeting methods
                (50/30/20, zero-based), insurance, taxes, and general financial literacy.
                Always connects the explanation to the user's real financial situation
                when context is available.
                Use this when the user asks "what is X", "explain X", "how does X work",
                or "what's the difference between X and Y".
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "topic", Map.of(
                        "type",        "string",
                        "description", "The financial term or concept to explain (e.g. 'Roth IRA', 'compound interest', 'APR')"
                    ),
                    "depth", Map.of(
                        "type",        "string",
                        "description", "How deep to go: basic (1-2 sentences), standard (full explanation), deep (with examples and math)",
                        "enum",        List.of("basic", "standard", "deep")
                    ),
                    "user_context", Map.of(
                        "type",        "string",
                        "description", "Optional: relevant user situation to personalize the explanation (e.g. 'user has payday loan debt')"
                    )
                ),
                "required", List.of("topic")
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        try {
            long startMs = System.currentTimeMillis();

            String topic       = input.path("topic").asText("").trim();
            String depth       = input.path("depth").asText("standard");
            String userContext = input.has("user_context")
                ? input.path("user_context").asText("") : "";

            if (topic.isBlank()) {
                return ToolResult.error("topic is required");
            }

            // Look up the topic in our knowledge base
            String topicKey   = normalizeTopicKey(topic);
            FinancialConcept concept = KNOWLEDGE_BASE.get(topicKey);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("topic",         topic);
            result.put("depth",         depth);
            result.put("found_in_kb",   concept != null);

            if (concept != null) {
                result.put("category",    concept.category());
                result.put("definition",  concept.definition());
                result.put("why_matters", concept.whyItMatters());
                result.put("key_numbers", concept.keyNumbers());
                result.put("common_mistakes", concept.commonMistakes());
                result.put("related_topics",  concept.relatedTopics());

                if ("deep".equals(depth) && concept.example() != null) {
                    result.put("example", concept.example());
                }
                if (!userContext.isBlank()) {
                    result.put("user_context_note", buildContextNote(topicKey, userContext));
                }
            } else {
                // Topic not in KB — pass to LLM with structured prompt hint
                result.put("instruction_to_agent",
                    "Topic '" + topic + "' not found in knowledge base. " +
                    "Explain it in plain language using these sections: " +
                    "1) Simple definition (1 sentence), " +
                    "2) Why it matters for personal finance, " +
                    "3) A concrete example with numbers, " +
                    "4) One common mistake to avoid. " +
                    (userContext.isBlank() ? "" : "Relate it to: " + userContext));
            }

            return ToolResult.okWithTiming(result, startMs);

        } catch (Exception e) {
            log.error("GetFinancialEducationTool error: {}", e.getMessage());
            return ToolResult.error("Failed to look up topic: " + e.getMessage());
        }
    }

    // ── Knowledge Base ────────────────────────────────────────────────────────

    private String normalizeTopicKey(String topic) {
        return topic.toLowerCase()
            .replaceAll("[^a-z0-9 ]", "")
            .replaceAll("\\s+", "_")
            .trim();
    }

    private String buildContextNote(String topicKey, String userContext) {
        String ctx = userContext.toLowerCase();
        return switch (topicKey) {
            case "apr", "annual_percentage_rate" ->
                ctx.contains("payday") ?
                    "Payday loans typically carry 300-400% APR. A $200 payday loan " +
                    "for 2 weeks at 400% APR costs ~$30 in fees — that's 15% in 14 days." : "";
            case "compound_interest" ->
                ctx.contains("debt") ?
                    "Compound interest works against you when you carry debt. " +
                    "Paying only the minimum on high-interest debt means interest accrues on interest." : "";
            case "emergency_fund" ->
                ctx.contains("overdraft") ?
                    "An emergency fund directly eliminates overdraft fees. " +
                    "Even $500 in a separate account acts as a buffer." : "";
            default -> "";
        };
    }

    private record FinancialConcept(
        String category,
        String definition,
        String whyItMatters,
        Map<String, String> keyNumbers,
        List<String> commonMistakes,
        List<String> relatedTopics,
        String example
    ) {}

    private static final Map<String, FinancialConcept> KNOWLEDGE_BASE = new LinkedHashMap<>();

    static {
        // ── Savings & Investment Vehicles ─────────────────────────────────────
        KNOWLEDGE_BASE.put("roth_ira", new FinancialConcept(
            "Retirement Accounts",
            "A Roth IRA is a retirement savings account where you contribute after-tax dollars, " +
            "and all growth and withdrawals in retirement are completely tax-free.",
            "It's one of the most powerful wealth-building tools available. " +
            "A 25-year-old who maxes out a Roth IRA every year could have $1M+ tax-free by retirement.",
            Map.of(
                "2024_contribution_limit", "$7,000/year ($8,000 if age 50+)",
                "income_limit_single",     "$161,000 (phase-out begins)",
                "income_limit_married",    "$240,000 (phase-out begins)",
                "penalty_free_withdrawal", "Age 59½ (contributions can be withdrawn anytime)",
                "required_distributions",  "None — unlike traditional IRA"
            ),
            List.of(
                "Waiting too long to start — compound growth needs time",
                "Investing in money market instead of index funds inside the Roth",
                "Withdrawing earnings early (10% penalty + taxes before 59½)",
                "Confusing Roth IRA with Roth 401k — different rules"
            ),
            List.of("Traditional IRA", "401k", "Index funds", "Compound interest", "Tax-advantaged accounts"),
            "Invest $7,000/year from age 25 to 65 in an S&P 500 index fund (avg 10%/yr return): " +
            "you'd contribute $280,000 total and have ~$3.1M — all tax-free."
        ));

        KNOWLEDGE_BASE.put("401k", new FinancialConcept(
            "Retirement Accounts",
            "A 401k is an employer-sponsored retirement savings account where contributions " +
            "are pre-tax, reducing your taxable income today. Employers often match contributions.",
            "The employer match is free money — a 100% instant return on your contribution. " +
            "Never leave employer match on the table.",
            Map.of(
                "2024_contribution_limit", "$23,000/year ($30,500 if age 50+)",
                "common_employer_match",   "3-6% of salary (varies by employer)",
                "tax_treatment",           "Pre-tax contributions, taxed on withdrawal",
                "early_withdrawal_penalty","10% + income taxes before age 59½"
            ),
            List.of(
                "Not contributing enough to get the full employer match",
                "Cashing out when changing jobs instead of rolling over",
                "Investing too conservatively when young (missing growth)",
                "Ignoring 401k fees — high expense ratios compound against you"
            ),
            List.of("Roth IRA", "Employer match", "Vesting schedule", "Rollover IRA"),
            "Earn $60,000/year. Contribute 6% ($3,600). Employer matches 3% ($1,800 free). " +
            "Your $3,600 becomes $5,400 instantly — a 50% return before any market growth."
        ));

        KNOWLEDGE_BASE.put("index_fund", new FinancialConcept(
            "Investments",
            "An index fund is a type of investment that tracks a market index (like the S&P 500) " +
            "by holding all or most of the stocks in that index, with very low fees.",
            "Index funds beat the majority of actively managed funds over 10+ year periods, " +
            "at a fraction of the cost. Warren Buffett recommends them for most investors.",
            Map.of(
                "typical_expense_ratio", "0.03% - 0.20% per year",
                "active_fund_avg_fee",   "0.5% - 1.5% per year",
                "sp500_historical_return","~10% average annual return (before inflation)",
                "fee_impact_30yr",       "1% fee difference = ~25% less money after 30 years"
            ),
            List.of(
                "Selling during market crashes — timing the market destroys returns",
                "Choosing high-fee funds when low-cost options exist (FXAIX, VTI, VTSAX)",
                "Confusing index funds with ETFs — both exist, slightly different structure",
                "Expecting consistent yearly returns — actual returns vary wildly year to year"
            ),
            List.of("S&P 500", "ETF", "Expense ratio", "Diversification", "Dollar-cost averaging"),
            "$10,000 invested in S&P 500 index fund 30 years ago would be ~$174,000 today. " +
            "The same amount in an average savings account: ~$18,000."
        ));

        KNOWLEDGE_BASE.put("emergency_fund", new FinancialConcept(
            "Budgeting & Savings",
            "An emergency fund is 3-6 months of living expenses kept in a liquid savings account, " +
            "used only for genuine emergencies (job loss, medical, car repair).",
            "It's the foundation of financial stability. Without it, any unexpected expense " +
            "forces debt — credit cards, payday loans, or borrowing from friends.",
            Map.of(
                "recommended_size",  "3 months (stable job) to 6 months (variable income)",
                "where_to_keep",     "High-yield savings account (4-5% APY in 2024)",
                "starter_goal",      "$500-$1,000 mini emergency fund first",
                "monthly_savings_needed", "Divide goal by 12 = monthly target"
            ),
            List.of(
                "Investing emergency fund in stocks — market could be down when you need it",
                "Using it for non-emergencies (sales, vacations, planned purchases)",
                "Keeping it in a regular checking account earning 0.01% instead of high-yield savings",
                "Not replenishing after using it"
            ),
            List.of("High-yield savings account", "Sinking funds", "Budget", "Debt avalanche"),
            "Monthly expenses: $2,500. Emergency fund target: $7,500 (3 months). " +
            "Save $300/month → fully funded in 25 months. " +
            "Stops the need for overdraft protection and payday loans."
        ));

        KNOWLEDGE_BASE.put("compound_interest", new FinancialConcept(
            "Core Concepts",
            "Compound interest is earning interest on your interest — your money grows " +
            "exponentially over time because each period's interest is added to the principal.",
            "Einstein allegedly called it the 8th wonder of the world. It works powerfully " +
            "FOR you in investments, and powerfully AGAINST you in debt.",
            Map.of(
                "rule_of_72",        "Divide 72 by interest rate = years to double your money",
                "example_10pct",     "$10,000 at 10% doubles in 7.2 years",
                "payday_loan_300pct","$500 loan at 300% APR costs $1,500+ if not paid in a year",
                "credit_card_20pct", "$5,000 balance paying minimum → 17 years to pay off"
            ),
            List.of(
                "Starting late — 10 years of delay can cost hundreds of thousands in retirement",
                "Not understanding it applies to debt too (credit cards, loans)",
                "Withdrawing investments and resetting the compound clock",
                "Underestimating how quickly high-interest debt compounds"
            ),
            List.of("Roth IRA", "APR", "Debt avalanche", "Rule of 72", "Time value of money"),
            "Invest $5,000 at age 25 and never add another dollar at 10%/yr: $226,000 at 65. " +
            "Wait until age 35: $87,000. That 10-year delay costs $139,000."
        ));

        KNOWLEDGE_BASE.put("apr", new FinancialConcept(
            "Debt & Credit",
            "APR (Annual Percentage Rate) is the yearly cost of borrowing money, " +
            "expressed as a percentage. It includes interest and fees.",
            "APR lets you compare the true cost of different loans and credit products. " +
            "A 'small' fee on a short-term loan can translate to 300%+ APR.",
            Map.of(
                "good_credit_card_apr",  "15-20%",
                "average_personal_loan", "10-28%",
                "payday_loan_apr",       "300-400% (typical)",
                "mortgage_2024",         "6.5-7.5%",
                "high_yield_savings",    "4.5-5.2% (earning, not paying)"
            ),
            List.of(
                "Focusing on monthly payment instead of total cost",
                "Not comparing APRs before taking a loan",
                "Confusing APR with APY (APY compounds, slightly higher)",
                "Ignoring fees that aren't included in advertised APR"
            ),
            List.of("Compound interest", "Credit score", "Payday loan", "Debt avalanche"),
            "Payday loan: borrow $200, pay back $230 in 2 weeks. " +
            "That's $30 fee on $200 for 14 days = 391% APR. " +
            "A credit card at 20% APR for the same $200 for 2 weeks = $1.54 in interest."
        ));

        KNOWLEDGE_BASE.put("credit_score", new FinancialConcept(
            "Debt & Credit",
            "A credit score (300-850) is a number that represents your creditworthiness — " +
            "how likely you are to repay debt. Lenders use it to decide loan terms and rates.",
            "A 100-point difference in credit score can cost or save $50,000+ over a lifetime " +
            "in higher/lower interest rates on mortgages and car loans.",
            Map.of(
                "excellent",            "800-850",
                "very_good",            "740-799",
                "good",                 "670-739",
                "fair",                 "580-669",
                "poor",                 "300-579",
                "payment_history_weight","35% of score",
                "credit_utilization_weight","30% of score (keep under 30%)"
            ),
            List.of(
                "Closing old credit cards (reduces available credit, hurts score)",
                "Applying for many cards at once (hard inquiries hurt score)",
                "Maxing out credit cards even if you pay in full (utilization matters)",
                "Not checking your free annual credit report for errors"
            ),
            List.of("APR", "Credit utilization", "Hard inquiry", "FICO score"),
            "760 vs 660 credit score on a $300,000 mortgage: " +
            "760 gets 6.5% APR, 660 gets 7.5%. Monthly difference: $200. " +
            "Over 30 years: $72,000 more in interest for the lower score."
        ));

        KNOWLEDGE_BASE.put("50_30_20_rule", new FinancialConcept(
            "Budgeting",
            "The 50/30/20 rule splits after-tax income into: 50% needs (rent, food, utilities), " +
            "30% wants (dining, entertainment, hobbies), and 20% savings and debt repayment.",
            "It's the simplest budgeting framework that covers all financial priorities. " +
            "Most people overspend on wants and underfund savings.",
            Map.of(
                "needs_50pct",    "Rent, groceries, utilities, insurance, minimum debt payments",
                "wants_30pct",    "Dining out, streaming, travel, hobbies, clothing",
                "savings_20pct",  "Emergency fund, retirement, extra debt payments",
                "on_3400_income", "Needs: $1,700 | Wants: $1,020 | Savings: $680"
            ),
            List.of(
                "Miscategorizing wants as needs (dining out is a want, not a need)",
                "Using gross income instead of after-tax income",
                "Not adjusting for high cost-of-living areas where 50% for needs isn't realistic",
                "Treating it as rigid — it's a guideline, adjust based on your goals"
            ),
            List.of("Zero-based budget", "Emergency fund", "Debt avalanche", "Savings rate"),
            "Income: $3,400/month after tax. " +
            "Needs budget: $1,700 (rent $950 + utilities $250 + groceries $300 + gas $200). " +
            "Wants budget: $1,020. Savings: $680/month → $8,160/year."
        ));

        KNOWLEDGE_BASE.put("debt_avalanche", new FinancialConcept(
            "Debt Payoff",
            "The debt avalanche method pays off debts in order of highest interest rate first, " +
            "making minimum payments on all others. Mathematically optimal — saves the most money.",
            "Paying highest-rate debt first minimizes total interest paid. " +
            "On $10,000 in mixed debt, avalanche vs minimum payments can save $5,000+.",
            Map.of(
                "vs_debt_snowball", "Snowball = lowest balance first (psychological wins), Avalanche = highest rate first (saves more money)",
                "first_step",       "List all debts by interest rate, highest to lowest",
                "execution",        "Pay minimums on all, throw every extra dollar at highest-rate debt"
            ),
            List.of(
                "Not making minimums on all debts (ruins credit, adds late fees)",
                "Switching strategies when it feels slow — stay consistent",
                "Not stopping new debt accumulation while paying off existing debt"
            ),
            List.of("Debt snowball", "APR", "Compound interest", "Credit score"),
            "Debts: Payday loan $500 at 400% APR, Credit card $2,000 at 22%, Car loan $8,000 at 7%. " +
            "Avalanche order: payday loan first (400%), then credit card (22%), then car (7%). " +
            "Eliminating the payday loan first stops the fastest-growing debt immediately."
        ));

        KNOWLEDGE_BASE.put("high_yield_savings_account", new FinancialConcept(
            "Savings",
            "A high-yield savings account (HYSA) is an FDIC-insured savings account " +
            "that pays 10-20x more interest than a traditional bank savings account.",
            "Your emergency fund sitting in a big-bank savings account at 0.01% APY " +
            "is losing money to inflation. An HYSA at 4.5-5% APY earns real returns.",
            Map.of(
                "typical_apy_2024",    "4.5-5.2% APY",
                "traditional_bank_apy","0.01-0.10% APY",
                "fdic_insured",        "Yes, up to $250,000",
                "top_providers",       "Ally, Marcus (Goldman Sachs), SoFi, Discover, Capital One 360"
            ),
            List.of(
                "Keeping emergency fund in checking account earning nothing",
                "Chasing the highest APY and switching constantly (taxes on interest)",
                "Confusing with money market accounts (similar but slightly different)",
                "Not opening one at all — takes 10 minutes online"
            ),
            List.of("Emergency fund", "APY vs APR", "FDIC insurance", "Money market account"),
            "$10,000 emergency fund: Traditional savings at 0.01% = $1/year interest. " +
            "HYSA at 4.8% = $480/year. Over 5 years: $2,400 more — for zero extra effort."
        ));
    }
}