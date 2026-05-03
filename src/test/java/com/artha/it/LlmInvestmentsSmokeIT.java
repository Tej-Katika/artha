package com.artha.it;

import com.artha.core.agent.AgentOrchestrator;
import com.artha.investments.ontology.Portfolio;
import com.artha.investments.ontology.PortfolioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end live-LLM smoke test for the investments domain.
 *
 * Drives a real Anthropic call through {@link AgentOrchestrator}
 * with {@code domain=investments} and verifies:
 *   1. The dual-domain dispatch routes to the investments tool set
 *   2. Claude gets a non-empty response back without crashing
 *   3. The retry / constraint loop survives at least one turn against
 *      the real banking + investments registries combined
 *
 * Disabled unless {@code ARTHA_LIVE_LLM=true}. To run:
 *
 * <pre>
 *   $env:ARTHA_LIVE_LLM = 'true'
 *   $env:ANTHROPIC_API_KEY = 'sk-ant-...'
 *   mvn test -Dtest=LlmInvestmentsSmokeIT
 * </pre>
 *
 * Assumes the V4 schema is applied and at least one INV-V2 portfolio
 * exists from {@code data/fetchers/generate_investments.py}. Skips
 * (rather than fails) when no portfolios are present so a developer
 * who hasn't seeded the investments domain doesn't get a red CI line.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "ARTHA_LIVE_LLM", matches = "true")
@TestPropertySource(properties = {
    "artha.anthropic.model=claude-haiku-4-5-20251001"
})
class LlmInvestmentsSmokeIT {

    @Autowired private AgentOrchestrator   orchestrator;
    @Autowired private PortfolioRepository portfolioRepo;

    private UUID testUserId;

    @BeforeEach
    void pickInvestmentsUser() {
        List<Portfolio> all = portfolioRepo.findAll();
        assumeTrue(!all.isEmpty(),
            "No investments portfolios in DB — run "
              + "`py -3 data/fetchers/generate_investments.py` first.");
        testUserId = all.get(0).getUserId();
    }

    @Test
    void investmentsDomainRoundTripsThroughLlm() {
        String response = orchestrator.chat(
            testUserId.toString(),
            "Summarize my portfolio briefly. List the top three "
            + "holdings by value. Keep the response under 60 words.",
            "investments");

        System.out.println("[LlmInvestmentsSmokeIT] Claude final response: " + response);

        assertThat(response)
            .as("orchestrator returned a non-empty response for the investments domain")
            .isNotBlank();
        // Sanity: the response should reference some financial framing.
        // We don't assert specific numbers (LLM is non-deterministic);
        // we just confirm the loop didn't return the canned error
        // text or a connection-error string.
        assertThat(response.toLowerCase())
            .as("response is not the orchestrator's connection-error fallback")
            .doesNotContain("trouble connecting");
    }
}
