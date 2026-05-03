package com.artha.it;

import com.artha.core.agent.AgentOrchestrator;
import com.artha.core.constraint.Constraint;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import com.artha.core.constraint.ViolationLog;
import com.artha.core.constraint.ViolationLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end live-LLM smoke test for the Constraints axis.
 *
 * Registers a synthetic SOFT constraint that always returns Violated,
 * then drives a real Anthropic call through {@link AgentOrchestrator}.
 * The orchestrator must:
 *   1. Run the constraint check after Claude returns end_turn
 *   2. Persist a {@link ViolationLog} row (REQUIRES_NEW)
 *   3. Append the synthetic repair prompt and re-enter the loop
 *   4. Repeat up to {@link AgentOrchestrator#MAX_CONSTRAINT_RETRIES}
 *      retries
 *   5. Stamp every row with {@code repaired=false} after K-exhaustion
 *
 * Why a synthetic constraint instead of an organic one: a real claim
 * extractor + real Claude rarely fires a HARD or SOFT violation
 * deterministically — Claude's tool-grounded responses are usually
 * clean. The synthetic constraint forces the retry path so the test
 * actually exercises the K-bounded repair loop end to end.
 *
 * Disabled unless {@code ARTHA_LIVE_LLM=true}. To run:
 *
 * <pre>
 *   $env:ARTHA_LIVE_LLM = 'true'
 *   $env:ANTHROPIC_API_KEY = 'sk-ant-...'
 *   mvn test -Dtest=LlmConstraintSmokeIT
 * </pre>
 *
 * Cost per run: roughly $0.10 (3 Sonnet 4.6 turns — initial + 2 retries).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(LlmConstraintSmokeIT.AlwaysViolateConfig.class)
@EnabledIfEnvironmentVariable(named = "ARTHA_LIVE_LLM", matches = "true")
@TestPropertySource(properties = {
    "artha.anthropic.model=claude-haiku-4-5-20251001"
})
class LlmConstraintSmokeIT {

    static final String SYNTHETIC_CONSTRAINT_NAME = "AlwaysViolatesForSmoke";

    /** Real seed user so user_id is non-null in violation_log. */
    private static final UUID TEST_USER = UUID.fromString(
        "aa000000-0000-0000-0000-000000000000");

    @Autowired private AgentOrchestrator      orchestrator;
    @Autowired private ViolationLogRepository violationRepo;

    private Instant testStartTime;

    @BeforeEach
    void setUp() {
        testStartTime = Instant.now();
    }

    @AfterEach
    void tearDown() {
        for (ViolationLog v : violationRepo.findAll()) {
            if (SYNTHETIC_CONSTRAINT_NAME.equals(v.getConstraintName())
                && v.getObservedAt() != null
                && v.getObservedAt().isAfter(testStartTime)) {
                violationRepo.delete(v);
            }
        }
    }

    @Test
    void syntheticConstraintForcesRetryLoopAndPersistsRows() {
        String response = orchestrator.chat(
            TEST_USER.toString(),
            "Hello — please briefly say what you can help with.",
            "banking");

        System.out.println("[LlmConstraintSmokeIT] Claude final response: " + response);

        assertThat(response)
            .as("orchestrator returned text after K-exhaustion")
            .isNotBlank();

        List<ViolationLog> mine = violationRepo.findAll().stream()
            .filter(v -> SYNTHETIC_CONSTRAINT_NAME.equals(v.getConstraintName()))
            .filter(v -> v.getObservedAt() != null
                      && v.getObservedAt().isAfter(testStartTime))
            .toList();

        assertThat(mine)
            .as("synthetic SOFT violation fires on every end_turn — "
              + "expect K+1 = 3 rows from initial attempt + 2 retries")
            .hasSize(AgentOrchestrator.MAX_CONSTRAINT_RETRIES + 1);

        assertThat(mine).allSatisfy(v -> {
            assertThat(v.getDomain()).isEqualTo("banking");
            assertThat(v.getGrade()).isEqualTo(ConstraintGrade.SOFT);
            assertThat(v.getUserId()).isEqualTo(TEST_USER);
            assertThat(v.getRepaired())
                .as("K-exhaustion → every row stamped repaired=false")
                .isFalse();
            assertThat(v.getSessionId()).isNotBlank();
            assertThat(v.getMessage()).contains("smoke-test");
        });

        assertThat(mine.stream().map(ViolationLog::getSessionId).distinct().count())
            .as("all retry attempts share one orchestrator session id")
            .isEqualTo(1L);
    }

    /**
     * Test-only Constraint bean. Spring picks it up alongside the
     * production banking constraints because {@link Import} adds it to
     * the application context, and ConstraintRegistry's @PostConstruct
     * discovers all Constraint beans regardless of source.
     */
    @TestConfiguration
    static class AlwaysViolateConfig {
        @Bean
        Constraint alwaysViolatesForSmoke() {
            return new Constraint() {
                @Override public String name()              { return SYNTHETIC_CONSTRAINT_NAME; }
                @Override public String domain()            { return "banking"; }
                @Override public ConstraintGrade grade()    { return ConstraintGrade.SOFT; }
                @Override public String repairHintTemplate(){ return "Smoke test always-violate."; }
                @Override
                public ConstraintResult evaluate(EvaluationContext ctx,
                                                 Set<FactualClaim> claims) {
                    return new ConstraintResult.Violated(
                        "smoke-test deterministic violation",
                        repairHintTemplate());
                }
            };
        }
    }
}
