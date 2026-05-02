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
 * Created in Week 2 of the IEEE v2 plan as a safety net for the
 * com.artha.agent.* -> com.artha.{core,banking}.* package refactor.
 * Without this, Spring config errors (broken @ComponentScan,
 * @EntityScan, @EnableJpaRepositories) only surface at app-startup
 * time -- too late to bisect.
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
            .as("expected exactly the 15 v1 tools -- registry: %s", registered)
            .containsExactlyInAnyOrderElementsOf(EXPECTED_V1_TOOLS);
    }

    /**
     * After the Week-2 refactor, ArthaAgentApplication is at the com.artha
     * root, so Spring scans com.artha.core.{action,provenance,constraint}
     * automatically. The v2 component beans are now scannable (registered
     * in the context) but are NOT yet wired into AgentOrchestrator's
     * reasoning loop. Wiring is the explicit step that gates each axis
     * going live in Weeks 3 / 5 / 6.
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
     * The v2 axes have empty registries until concrete Actions /
     * Constraints land in banking/investments domains (Weeks 3 / 6 / 8).
     * Verifying empty registries here confirms no accidental discovery
     * of unfinished implementations.
     */
    @Test
    void v2RegistriesAreEmptyBeforeAxesGoLive() {
        com.artha.core.action.ActionRegistry actions =
            ctx.getBean(com.artha.core.action.ActionRegistry.class);
        com.artha.core.constraint.ConstraintRegistry constraints =
            ctx.getBean(com.artha.core.constraint.ConstraintRegistry.class);

        assertThat(actions.size())
            .as("no Actions implemented yet")
            .isZero();
        assertThat(constraints.size())
            .as("no Constraints implemented yet")
            .isZero();
    }
}
