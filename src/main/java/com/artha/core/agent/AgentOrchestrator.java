package com.artha.core.agent;

import com.artha.core.constraint.ConstraintChecker;
import com.artha.core.constraint.ConstraintChecker.CheckResult;
import com.artha.core.constraint.ConstraintChecker.Violation;
import com.artha.core.constraint.ViolationLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Artha Agent Orchestrator
 *
 * Manages the Claude tool-calling loop via direct HTTP:
 *   1. User message arrives
 *   2. Call Claude with message + tool definitions
 *   3. If Claude returns tool_use -> execute tool -> send result back
 *   4. Repeat until Claude returns a text response or MAX_TURNS is hit
 *   5. Run the {@link ConstraintChecker} on the final text. On HARD or
 *      SOFT violations, append a synthetic repair-prompt as a user
 *      message and re-enter the loop. Up to {@link #MAX_CONSTRAINT_RETRIES}
 *      retries; thereafter return whatever Claude produced.
 *
 * Every fired violation is persisted via {@link ViolationLogService}.
 * The {@code repaired} flag is updated once the session terminates:
 * {@code true} if the final response was satisfied, {@code false} on
 * K-exhaustion. Earlier-attempt rows share the session-level outcome.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    @Value("${artha.anthropic.api-key}")
    private String apiKey;

    @Value("${artha.anthropic.model:claude-haiku-4-5-20251001}")
    private String model;

    private final ToolRegistry         toolRegistry;
    private final ObjectMapper         objectMapper;
    private final ConstraintChecker    constraintChecker;
    private final ViolationLogService  violationLogService;

    private static final int    MAX_TURNS                = 8;
    public static final  int    MAX_CONSTRAINT_RETRIES   = 2;
    private static final String API_URL                  = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION              = "2023-06-01";
    private static final String DEFAULT_DOMAIN           = "banking";

    private static final String SYSTEM_PROMPT =
        "You are Artha, a personal AI financial advisor.\n" +
        "You have access to the user's real bank transaction data through tools.\n\n" +
        "Rules:\n" +
        "- ALWAYS use tools to fetch real data before answering financial questions.\n" +
        "- Never guess amounts — only report numbers returned by tools.\n" +
        "- Be specific: say '$342.50 on dining' not 'you spent a lot on food'.\n" +
        "- Keep responses concise and actionable.\n" +
        "- If a tool returns an error, tell the user clearly and suggest what to try.\n" +
        "- For complex questions, chain multiple tool calls to gather full context.\n\n" +
        "You are a smart, honest financial friend — not a salesperson.";

    /** Backward-compatible entry — defaults to the banking domain. */
    public String chat(String userId, String message) {
        return chat(userId, message, DEFAULT_DOMAIN);
    }

    public String chat(String userId, String message, String domain) {
        log.info("Agent chat — userId={} domain={} message={}", userId, domain, message);

        String sessionId = UUID.randomUUID().toString();
        UUID   userUuid  = parseUserUuid(userId);
        List<UUID> sessionViolationIds = new ArrayList<>();
        int constraintAttempts = 0;

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role",    "user");
        userMsg.put("content", message);
        messages.add(userMsg);

        ArrayNode tools = buildToolDefinitions();

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            JsonNode response = callClaude(messages, tools);
            if (response == null) {
                violationLogService.markRepaired(sessionViolationIds, false);
                return "I'm having trouble connecting right now. Please try again in a moment.";
            }

            String   stopReason = response.path("stop_reason").asText("");
            JsonNode content    = response.path("content");

            ObjectNode assistantMsg = objectMapper.createObjectNode();
            assistantMsg.put("role", "assistant");
            assistantMsg.set("content", content);
            messages.add(assistantMsg);

            if ("end_turn".equals(stopReason) || "stop_sequence".equals(stopReason)) {
                String text = extractText(content);

                CheckResult cr = checkConstraints(domain, userUuid, text);
                persistViolations(cr, domain, userUuid, sessionId, sessionViolationIds);

                RetryDecision rd = decideRetry(cr, constraintAttempts, MAX_CONSTRAINT_RETRIES);
                if (!rd.retry()) {
                    finalizeSession(sessionViolationIds, cr.satisfied());
                    return text;
                }

                log.info("Constraint violations triggered repair retry {}/{} (session={})",
                    constraintAttempts + 1, MAX_CONSTRAINT_RETRIES, sessionId);
                constraintAttempts++;
                ObjectNode repairMsg = objectMapper.createObjectNode();
                repairMsg.put("role",    "user");
                repairMsg.put("content", rd.repairPrompt());
                messages.add(repairMsg);
                continue;
            }

            if ("tool_use".equals(stopReason)) {
                ArrayNode toolResults = objectMapper.createArrayNode();
                for (JsonNode block : content) {
                    if ("tool_use".equals(block.path("type").asText())) {
                        String toolName  = block.path("name").asText();
                        String toolUseId = block.path("id").asText();
                        JsonNode input   = block.path("input");

                        log.info("Tool call — name={} userId={}", toolName, userId);
                        String result = executeTool(toolName, input, userId);

                        ObjectNode resultBlock = objectMapper.createObjectNode();
                        resultBlock.put("type",        "tool_result");
                        resultBlock.put("tool_use_id", toolUseId);
                        resultBlock.put("content",     result);
                        toolResults.add(resultBlock);
                    }
                }
                ObjectNode toolResultMsg = objectMapper.createObjectNode();
                toolResultMsg.put("role", "user");
                toolResultMsg.set("content", toolResults);
                messages.add(toolResultMsg);
            } else {
                String text = extractText(content);
                finalizeSession(sessionViolationIds, false);
                return text;
            }
        }

        log.warn("Agent hit MAX_TURNS={} for userId={}", MAX_TURNS, userId);
        finalizeSession(sessionViolationIds, false);
        return "I need more information to fully answer that. Could you clarify what you'd like to know?";
    }

    // ── constraint helpers ──────────────────────────────────────────

    private CheckResult checkConstraints(String domain, UUID userUuid, String text) {
        if (userUuid == null) {
            return new CheckResult(List.of(), 0);
        }
        try {
            return constraintChecker.check(domain, userUuid, Instant.now(), text);
        } catch (RuntimeException ex) {
            log.warn("Constraint check threw — skipping: {}", ex.getMessage());
            return new CheckResult(List.of(), 0);
        }
    }

    private void persistViolations(CheckResult cr,
                                   String domain,
                                   UUID userUuid,
                                   String sessionId,
                                   List<UUID> sessionViolationIds) {
        for (Violation v : cr.violations()) {
            try {
                UUID id = violationLogService.persist(v, domain, userUuid, sessionId);
                sessionViolationIds.add(id);
            } catch (RuntimeException ex) {
                log.warn("ViolationLog persist failed (constraint={} session={}): {}",
                    v.constraintName(), sessionId, ex.getMessage());
            }
        }
    }

    private void finalizeSession(List<UUID> ids, boolean repaired) {
        if (ids.isEmpty()) return;
        try {
            violationLogService.markRepaired(ids, repaired);
        } catch (RuntimeException ex) {
            log.warn("ViolationLog markRepaired failed: {}", ex.getMessage());
        }
    }

    private static UUID parseUserUuid(String userId) {
        if (userId == null || userId.isBlank()) return null;
        try { return UUID.fromString(userId); }
        catch (IllegalArgumentException ex) {
            log.warn("Could not parse userId as UUID — constraints disabled for this call: {}", userId);
            return null;
        }
    }

    // ── retry decision (pure helper, unit-testable) ─────────────────

    /** Outcome of {@link #decideRetry}: whether to re-prompt and the prompt to use. */
    record RetryDecision(boolean retry, String repairPrompt) {}

    /**
     * Pure function — given a constraint check result and the number of
     * retries already performed, decide whether to re-prompt and what
     * to say. HARD and SOFT both trigger retry; ADVISORY does not.
     * No retry once the budget is exhausted.
     */
    static RetryDecision decideRetry(CheckResult result,
                                     int attemptCount,
                                     int maxAttempts) {
        if (result == null) return new RetryDecision(false, null);
        if (!result.hasHard() && !result.hasSoft()) {
            return new RetryDecision(false, null);
        }
        if (attemptCount >= maxAttempts) {
            return new RetryDecision(false, null);
        }
        return new RetryDecision(true, buildRepairPrompt(result.violations()));
    }

    static String buildRepairPrompt(List<Violation> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("Your previous response had ")
          .append(violations.size())
          .append(" factual or integrity issue(s):\n\n");
        int i = 1;
        for (Violation v : violations) {
            sb.append(i++).append(". [").append(v.constraintName()).append("] ")
              .append(v.message()).append('\n');
            if (v.repairHint() != null && !v.repairHint().isBlank()) {
                sb.append("   How to fix: ").append(v.repairHint()).append('\n');
            }
        }
        sb.append("\nPlease revise your response. Use tools to re-verify any "
                + "numeric or categorical claims, and only state facts supported "
                + "by tool output.");
        return sb.toString();
    }

    // ── existing Claude transport / tooling (unchanged) ─────────────

    private JsonNode callClaude(ArrayNode messages, ArrayNode tools) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model",      model);
            body.put("max_tokens", 1024);
            body.put("system",     SYSTEM_PROMPT);
            body.set("messages",   messages);
            body.set("tools",      tools);

            String responseBody = WebClient.builder()
                .baseUrl(API_URL)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("x-api-key",         apiKey)
                .defaultHeader("anthropic-version", API_VERSION)
                .build()
                .post()
                .bodyValue(body.toString())
                .retrieve()
                .bodyToMono(String.class)
                .block();

            return objectMapper.readTree(responseBody);

        } catch (WebClientResponseException e) {
            log.error("Claude API error — status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.error("Claude call failed — {}", e.getMessage(), e);
            return null;
        }
    }

    private String executeTool(String name, JsonNode input, String userId) {
        FinancialTool tool = toolRegistry.getTool(name);
        if (tool == null) {
            log.warn("Unknown tool requested: {}", name);
            return "{\"error\":\"Unknown tool: " + name + "\"}";
        }
        try {
            ToolContext context = ToolContext.of(userId, "default", "claude");
            ToolResult result   = tool.execute(input, context);
            if (result.success()) {
                return objectMapper.writeValueAsString(result.data());
            } else {
                return "{\"error\":\"" + result.errorMessage() + "\"}";
            }
        } catch (Exception e) {
            log.error("Tool execution error — name={} error={}", name, e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private ArrayNode buildToolDefinitions() {
        try {
            return toolRegistry.buildToolDefinitions();
        } catch (Exception e) {
            log.error("Failed to build tool definitions: {}", e.getMessage(), e);
            return objectMapper.createArrayNode();
        }
    }

    private String extractText(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
        }
        return sb.toString().trim();
    }
}
