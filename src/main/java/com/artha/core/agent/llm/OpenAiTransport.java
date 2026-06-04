package com.artha.core.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * OpenAI Chat Completions transport (e.g., GPT-5) as a drop-in agent backbone.
 *
 * <p>Adapter: translates the orchestrator's Anthropic-shaped request into the
 * Chat Completions schema (system message, {@code tool_calls} on assistant
 * turns, {@code role:tool} result messages, {@code tools[].function}) and
 * translates the response back into Anthropic-shaped
 * {@code {stop_reason, content[]}} so the orchestrator loop is unchanged.
 *
 * <p>Tool-call IDs round-trip: an Anthropic {@code tool_use.id} we emit becomes
 * the OpenAI {@code tool_calls[].id}; the orchestrator's matching
 * {@code tool_result.tool_use_id} becomes the {@code role:tool} message's
 * {@code tool_call_id}, satisfying OpenAI's pairing requirement.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiTransport implements LlmTransport {

    @Value("${artha.openai.api-key:not-set}")
    private String apiKey;

    @Value("${artha.openai.model:gpt-5}")
    private String model;

    @Value("${artha.openai.base-url:https://api.openai.com/v1/chat/completions}")
    private String baseUrl;

    @Value("${artha.openai.max-completion-tokens:4096}")
    private int maxCompletionTokens;

    private final ObjectMapper objectMapper;

    private static final int  UPSTREAM_MAX_ATTEMPTS    = 4;
    private static final long UPSTREAM_BACKOFF_BASE_MS = 2000L;

    @Override
    public String provider() { return "openai"; }

    @Override
    public JsonNode call(ArrayNode messages, ArrayNode tools, String system) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_completion_tokens", maxCompletionTokens);
        body.set("messages", toOpenAiMessages(messages, system));
        ArrayNode oaTools = toOpenAiTools(tools);
        if (oaTools.size() > 0) body.set("tools", oaTools);
        String bodyStr = body.toString();

        for (int attempt = 1; attempt <= UPSTREAM_MAX_ATTEMPTS; attempt++) {
            try {
                String responseBody = WebClient.builder()
                    .baseUrl(baseUrl)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .build()
                    .post()
                    .bodyValue(bodyStr)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

                JsonNode parsed = objectMapper.readTree(responseBody);
                return fromOpenAiResponse(parsed);

            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = (status == 429 || status >= 500)
                                    && attempt < UPSTREAM_MAX_ATTEMPTS;
                if (retryable) {
                    long waitMs = UPSTREAM_BACKOFF_BASE_MS * (1L << (attempt - 1));
                    log.warn("OpenAI API {} on attempt {}/{} — retrying in {}ms",
                            status, attempt, UPSTREAM_MAX_ATTEMPTS, waitMs);
                    try { Thread.sleep(waitMs); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                log.error("OpenAI API error — status={} body={}",
                        e.getStatusCode(), e.getResponseBodyAsString());
                return null;
            } catch (Exception e) {
                log.error("OpenAI call failed — {}", e.getMessage(), e);
                return null;
            }
        }
        return null;
    }

    // ── request translation: Anthropic dialect -> Chat Completions ──────

    private ArrayNode toOpenAiMessages(ArrayNode messages, String system) {
        ArrayNode out = objectMapper.createArrayNode();
        ObjectNode sys = objectMapper.createObjectNode();
        sys.put("role", "system");
        sys.put("content", system);
        out.add(sys);

        for (JsonNode m : messages) {
            String role = m.path("role").asText();
            JsonNode content = m.path("content");

            if (content.isTextual()) {
                // plain user (or repair) message
                ObjectNode msg = objectMapper.createObjectNode();
                msg.put("role", role);
                msg.put("content", content.asText());
                out.add(msg);
                continue;
            }

            if ("assistant".equals(role) && content.isArray()) {
                StringBuilder text = new StringBuilder();
                ArrayNode toolCalls = objectMapper.createArrayNode();
                for (JsonNode block : content) {
                    String type = block.path("type").asText();
                    if ("text".equals(type)) {
                        text.append(block.path("text").asText());
                    } else if ("tool_use".equals(type)) {
                        ObjectNode tc = objectMapper.createObjectNode();
                        tc.put("id", block.path("id").asText());
                        tc.put("type", "function");
                        ObjectNode fn = objectMapper.createObjectNode();
                        fn.put("name", block.path("name").asText());
                        fn.put("arguments", block.path("input").toString());
                        tc.set("function", fn);
                        toolCalls.add(tc);
                    }
                }
                ObjectNode msg = objectMapper.createObjectNode();
                msg.put("role", "assistant");
                if (text.length() > 0) msg.put("content", text.toString());
                else msg.putNull("content");
                if (toolCalls.size() > 0) msg.set("tool_calls", toolCalls);
                out.add(msg);
                continue;
            }

            if ("user".equals(role) && content.isArray()) {
                // tool_result blocks -> separate role:tool messages
                for (JsonNode block : content) {
                    if ("tool_result".equals(block.path("type").asText())) {
                        ObjectNode msg = objectMapper.createObjectNode();
                        msg.put("role", "tool");
                        msg.put("tool_call_id", block.path("tool_use_id").asText());
                        msg.put("content", block.path("content").asText());
                        out.add(msg);
                    }
                }
            }
        }
        return out;
    }

    private ArrayNode toOpenAiTools(ArrayNode tools) {
        ArrayNode out = objectMapper.createArrayNode();
        if (tools == null) return out;
        for (JsonNode t : tools) {
            ObjectNode wrap = objectMapper.createObjectNode();
            wrap.put("type", "function");
            ObjectNode fn = objectMapper.createObjectNode();
            fn.put("name", t.path("name").asText());
            fn.put("description", t.path("description").asText());
            JsonNode schema = t.path("input_schema");
            fn.set("parameters", schema.isMissingNode()
                ? objectMapper.createObjectNode() : schema);
            wrap.set("function", fn);
            out.add(wrap);
        }
        return out;
    }

    // ── response translation: Chat Completions -> Anthropic dialect ─────

    private JsonNode fromOpenAiResponse(JsonNode parsed) {
        JsonNode message = parsed.path("choices").path(0).path("message");
        ArrayNode content = objectMapper.createArrayNode();

        JsonNode textNode = message.path("content");
        if (textNode.isTextual() && !textNode.asText().isBlank()) {
            ObjectNode textBlock = objectMapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", textNode.asText());
            content.add(textBlock);
        }

        JsonNode toolCalls = message.path("tool_calls");
        boolean hasToolCalls = toolCalls.isArray() && toolCalls.size() > 0;
        if (hasToolCalls) {
            for (JsonNode tc : toolCalls) {
                ObjectNode block = objectMapper.createObjectNode();
                block.put("type", "tool_use");
                block.put("id", tc.path("id").asText());
                block.put("name", tc.path("function").path("name").asText());
                block.set("input", parseArgs(tc.path("function").path("arguments").asText("")));
                content.add(block);
            }
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.put("stop_reason", hasToolCalls ? "tool_use" : "end_turn");
        out.set("content", content);
        return out;
    }

    private JsonNode parseArgs(String args) {
        if (args == null || args.isBlank()) return objectMapper.createObjectNode();
        try { return objectMapper.readTree(args); }
        catch (Exception e) {
            log.warn("OpenAI tool arguments not valid JSON — {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }
}
