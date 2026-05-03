package com.artha;

import com.artha.core.agent.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: Spring context loads cleanly, every v1 tool is registered,
 * and every v2 core component is scannable (post-Week-2-refactor).
 *
 * Acts as a safety net for the com.artha.agent.* ->
 * com.artha.{core,banking,investments}.* package layout. Without
 * this, Spring config errors (broken @ComponentScan, @EntityScan,
 * @EnableJpaRepositories) only surface at app-startup time --
 * too late to bisect.
 *
 * Requires a live PostgreSQL on localhost:5432 (per application.yml).
 * Does NOT call the Anthropic API; the API key may be "not-set".
 *
 * Web environment is NONE so the test does not bind a port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationContextSmokeTest {

    /** All v1 tools as of 2026-05-01 (Week 2 baseline). */
    private static final Set<String> EXPECTED_V1_TOOLS = Set.of(
        "get_anomalies",
        "get_budget_status",
        "get_behavioral_patterns",
        "get_category_insights",
        "get_financial_education",
        "get_financial_health",
        "get_goal_impact",
        "get_goal_progress",
        "get_goal_projection",
        "get_income_vs_spend",
        "get_investment_projection",
        "get_spending_summary",
        "get_subscriptions",
        "get_transactions",
        "get_what_if_analysis"
    );

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void contextLoads() {
        assertThat(ctx).isNotNull();
        assertThat(toolRegistry).isNotNull();
    }

    @Test
    void allV1ToolsAreRegistered() {
        Set<String> registered = toolRegistry.getRegisteredNames();
        assertThat(registered)
            .as("v1 tool set must remain present -- registry: %s", registered)
            .containsAll(EXPECTED_V1_TOOLS);
    }

    /**
     * Week-3+ tools that wrap typed Actions. SplitTransaction is
     * deferred — needs a schema decision (TransactionEnrichment is
     * 1:1 with Transaction; splits would need either a new table or
     * a JSON splits column).
     */
    @Test
    void v2BankingActionToolsAreRegistered() {
        assertThat(toolRegistry.getRegisteredNames())
            .contains(
                "recategorize_transaction",
                "create_goal",
                "dismiss_anomaly",
                "update_budget",
                "mark_recurring"
            );
    }

    /**
     * Provenance axis went live in Week 5 — the read-only
     * get_fact_provenance tool exposes ProvenanceService to the agent.
     */
    @Test
    void v2ProvenanceToolIsRegistered() {
        assertThat(toolRegistry.getRegisteredNames())
            .contains("get_fact_provenance");
    }

    /**
     * Constraints axis (Week 6) — full banking set: 5 HARD (3 ontology,
     * 1 numeric, 1 enum) + 3 SOFT (claim-driven). Investments-domain
     * constraints land in Week 8.
     */
    @Test
    void v2BankingConstraintsAreRegistered() {
        com.artha.core.constraint.ConstraintRegistry constraints =
            ctx.getBean(com.artha.core.constraint.ConstraintRegistry.class);
        assertThat(constraints.forDomain("banking"))
            .extracting(com.artha.core.constraint.Constraint::name)
            .contains(
                "GoalProgressBound",
                "AnomalyEvidence",
                "SpendingMagnitude",
                "RecurringCadence",
                "CategoryMutex",
                "BudgetArithmetic",
                "MerchantClassMatch",
                "DateRangeBounding"
            );
    }

    /**
     * After the Week-2 refactor, ArthaAgentApplication is at the com.artha
     * root, so Spring scans com.artha.core.{action,provenance,constraint}
     * automatically. The v2 component beans are scannable; concrete
     * Actions / Constraints get registered as their domain implementations
     * land.
     */
    @Test
    void v2CorePackagesAreScannable() {
        assertThat(ctx.getBeanNamesForType(com.artha.core.action.ActionRegistry.class))
            .as("ActionRegistry should be scanned post-refactor")
            .isNotEmpty();
        assertThat(ctx.getBeanNamesForType(com.artha.core.action.ActionExecutor.class))
            .as("ActionExecutor should be scanned post-refactor")
            .isNotEmpty();
        assertThat(ctx.getBeanNamesForType(com.artha.core.constraint.ConstraintRegistry.class))
            .as("ConstraintRegistry should be scanned post-refactor")
            .isNotEmpty();
        assertThat(ctx.getBeanNamesForType(com.artha.core.constraint.ConstraintChecker.class))
            .as("ConstraintChecker should be scanned post-refactor")
            .isNotEmpty();
        assertThat(ctx.getBeanNamesForType(com.artha.core.provenance.ProvenanceCombiner.class))
            .as("ProvenanceCombiner should be scanned post-refactor")
            .isNotEmpty();
        assertThat(ctx.getBeanNamesForType(com.artha.core.provenance.ProvenanceService.class))
            .as("ProvenanceService should be scanned post-refactor")
            .isNotEmpty();
    }

    /**
     * Investments domain ontology (Week 7) — 9 entity repositories +
     * 2 reference-data repositories (DailyPrice, RiskFreeRate). Asserts
     * every repository bean is in the application context, which proves
     * the JPA entity scan reaches `com.artha.investments.ontology.*`
     * and the V4 schema is mapped without ddl-auto=validate failures.
     */
    @Test
    void v2InvestmentsOntologyRepositoriesAreScannable() {
        assertThat(ctx.getBeanNamesForType(
            com.artha.investments.ontology.SecurityRepository.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(
            com.artha.investments.ontology.BenchmarkRepository.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(
            com.artha.investments.ontology.DailyPriceRepository.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(
            com.artha.investments.ontology.RiskFreeRateRepository.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(
            com.artha.investments.ontology.PortfolioRepository.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(
            com.artha.investments.ontology.PositionRepository.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(
            com.artha.investments.ontology.LotRepository.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(
            com.artha.investments.ontology.TradeRepository.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(
            com.artha.investments.ontology.DividendRepository.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(
            com.artha.investments.ontology.FeeRepository.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(
            com.artha.investments.ontology.RiskProfileRepository.class)).isNotEmpty();
    }

    /**
     * Tracks how many Actions / Constraints are wired in. Banking
     * targets: 6 actions (5 shipped, split-transaction deferred), 8
     * constraints. Investments-domain Actions / Constraints follow
     * a separate roadmap. Update the expected minimums as each axis
     * fills in.
     */
    @Test
    void v2RegistrySizesReflectImplementedAxes() {
        com.artha.core.action.ActionRegistry actions =
            ctx.getBean(com.artha.core.action.ActionRegistry.class);
        com.artha.core.constraint.ConstraintRegistry constraints =
            ctx.getBean(com.artha.core.constraint.ConstraintRegistry.class);

        // Week 3: 5 of 6 banking actions in (SplitTransaction deferred)
        assertThat(actions.registeredKeys())
            .as("Week-3 banking Actions registered so far")
            .contains(
                "banking::RecategorizeTransaction",
                "banking::CreateGoal",
                "banking::DismissAnomaly",
                "banking::UpdateBudget",
                "banking::MarkRecurring"
            );

        // Week 6 complete: 8 banking constraints registered.
        assertThat(constraints.size())
            .as("Week-6 constraints — full banking set")
            .isGreaterThanOrEqualTo(8);
    }
}
