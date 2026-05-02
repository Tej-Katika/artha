package com.artha.core.agent;

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

/**
 * Artha Agent Orchestrator
 *
 * Manages the Claude tool-calling loop via direct HTTP:
 *   1. User message arrives
 *   2. Call Claude with message + tool definitions
 *   3. If Claude returns tool_use -> execute tool -> send result back
 *   4. Repeat until Claude returns a text response or MAX_TURNS is hit
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    @Value("${artha.anthropic.api-key}")
    private String apiKey;

    @Value("${artha.anthropic.model:claude-haiku-4-5-20251001}")
    private String model;

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    private static final int    MAX_TURNS   = 8;
    private static final String API_URL     = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private static final String SYSTEM_PROMPT =
        "You are Artha, a personal AI financial advisor.\n" +
        "You have access to the user's real bank transaction data through tools.\n\n" +
        "Rules:\n" +
        "- ALWAYS use tools to fetch real data before answering financial questions.\n" +
        "- Never guess amounts â€” only report numbers returned by tools.\n" +
        "- Be specific: say '$342.50 on dining' not 'you spent a lot on food'.\n" +
        "- Keep responses concise and actionable.\n" +
        "- If a tool returns an error, tell the user clearly and suggest what to try.\n" +
        "- For complex questions, chain multiple tool calls to gather full context.\n\n" +
        "You are a smart, honest financial friend â€” not a salesperson.";

    public String chat(String userId, String message) {
        log.info("Agent chat â€” userId={} message={}", userId, message);

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", message);
        messages.add(userMsg);

        ArrayNode tools = buildToolDefinitions();

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            JsonNode response = callClaude(messages, tools);
            if (response == null) {
                return "I'm having trouble connecting right now. Please try again in a moment.";
            }

            String stopReason = response.path("stop_reason").asText("");
            JsonNode content  = response.path("content");

            ObjectNode assistantMsg = objectMapper.createObjectNode();
            assistantMsg.put("role", "assistant");
            assistantMsg.set("content", content);
            messages.add(assistantMsg);

            if ("end_turn".equals(stopReason) || "stop_sequence".equals(stopReason)) {
                return extractText(content);
            }

            if ("tool_use".equals(stopReason)) {
                ArrayNode toolResults = objectMapper.createArrayNode();
                for (JsonNode block : content) {
                    if ("tool_use".equals(block.path("type").asText())) {
                        String toolName  = block.path("name").asText();
                        String toolUseId = block.path("id").asText();
                        JsonNode input   = block.path("input");

                        log.info("Tool call â€” name={} userId={}", toolName, userId);
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
                return extractText(content);
            }
        }

        log.warn("Agent hit MAX_TURNS={} for userId={}", MAX_TURNS, userId);
        return "I need more information to fully answer that. Could you clarify what you'd like to know?";
    }

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
            log.error("Claude API error â€” status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.error("Claude call failed â€” {}", e.getMessage(), e);
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
            log.error("Tool execution error â€” name={} error={}", name, e.getMessage(), e);
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