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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Google Gemini {@code generateContent} transport as a drop-in agent backbone.
 *
 * <p>Adapter: translates the orchestrator's Anthropic-shaped request into
 * Gemini's schema ({@code system_instruction}, {@code contents} with
 * {@code functionCall}/{@code functionResponse} parts, {@code tools[].function_declarations})
 * and translates the response back into Anthropic-shaped
 * {@code {stop_reason, content[]}}.
 *
 * <p>Two Gemini-specific wrinkles are handled: (1) Gemini returns no tool-call
 * IDs, so we synthesize stable IDs and recover the function name for a
 * {@code functionResponse} by mapping the orchestrator's
 * {@code tool_result.tool_use_id} back through the assistant's emitted
 * {@code tool_use} blocks; (2) Gemini's function-declaration parameter schema
 * rejects JSON-Schema meta keys, so {@link #sanitizeSchema} keeps only the
 * supported subset.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiTransport implements LlmTransport {

    @Value("${artha.gemini.api-key:not-set}")
    private String apiKey;

    @Value("${artha.gemini.model:gemini-2.5-pro}")
    private String model;

    @Value("${artha.gemini.base-url:https://generativelanguage.googleapis.com/v1beta/models}")
    private String baseUrl;

    @Value("${artha.gemini.max-output-tokens:4096}")
    private int maxOutputTokens;

    private final ObjectMapper objectMapper;

    private static final int  UPSTREAM_MAX_ATTEMPTS    = 4;
    private static final long UPSTREAM_BACKOFF_BASE_MS = 2000L;
    private static final Set<String> SCHEMA_KEYS =
        Set.of("type", "description", "properties", "required", "items", "enum", "nullable");

    @Override
    public String provider() { return "gemini"; }

    @Override
    public JsonNode call(ArrayNode messages, ArrayNode tools, String system) {
        Map<String, String> idToName = buildIdNameMap(messages);

        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode sysInstr = objectMapper.createObjectNode();
        ArrayNode sysParts = objectMapper.createArrayNode();
        sysParts.add(objectMapper.createObjectNode().put("text", system));
        sysInstr.set("parts", sysParts);
        body.set("system_instruction", sysInstr);
        body.set("contents", toGeminiContents(messages, idToName));
        ArrayNode gtools = toGeminiTools(tools);
        if (gtools.size() > 0) body.set("tools", gtools);
        ObjectNode genCfg = objectMapper.createObjectNode();
        genCfg.put("maxOutputTokens", maxOutputTokens);
        body.set("generationConfig", genCfg);
        String bodyStr = body.toString();

        String url = baseUrl + "/" + model + ":generateContent";
        for (int attempt = 1; attempt <= UPSTREAM_MAX_ATTEMPTS; attempt++) {
            try {
                String responseBody = WebClient.builder()
                    .baseUrl(url)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .defaultHeader("x-goog-api-key", apiKey)
                    .build()
                    .post()
                    .bodyValue(bodyStr)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

                JsonNode parsed = objectMapper.readTree(responseBody);
                return fromGeminiResponse(parsed);

            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = (status == 429 || status >= 500)
                                    && attempt < UPSTREAM_MAX_ATTEMPTS;
                if (retryable) {
                    long waitMs = UPSTREAM_BACKOFF_BASE_MS * (1L << (attempt - 1));
                    log.warn("Gemini API {} on attempt {}/{} — retrying in {}ms",
                            status, attempt, UPSTREAM_MAX_ATTEMPTS, waitMs);
                    try { Thread.sleep(waitMs); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                log.error("Gemini API error — status={} body={}",
                        e.getStatusCode(), e.getResponseBodyAsString());
                return null;
            } catch (Exception e) {
                log.error("Gemini call failed — {}", e.getMessage(), e);
                return null;
            }
        }
        return null;
    }

    /** Map every emitted tool_use id -> tool name, for functionResponse naming. */
    private Map<String, String> buildIdNameMap(ArrayNode messages) {
        Map<String, String> map = new HashMap<>();
        for (JsonNode m : messages) {
            if (!"assistant".equals(m.path("role").asText())) continue;
            JsonNode content = m.path("content");
            if (!content.isArray()) continue;
            for (JsonNode block : content) {
                if ("tool_use".equals(block.path("type").asText())) {
                    map.put(block.path("id").asText(), block.path("name").asText());
                }
            }
        }
        return map;
    }

    // ── request translation: Anthropic dialect -> Gemini ────────────────

    private ArrayNode toGeminiContents(ArrayNode messages, Map<String, String> idToName) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode m : messages) {
            String role = m.path("role").asText();
            JsonNode content = m.path("content");

            if (content.isTextual()) {
                out.add(textContent("user", content.asText()));
                continue;
            }

            if ("assistant".equals(role) && content.isArray()) {
                ArrayNode parts = objectMapper.createArrayNode();
                for (JsonNode block : content) {
                    String type = block.path("type").asText();
                    if ("text".equals(type)) {
                        parts.add(objectMapper.createObjectNode().put("text", block.path("text").asText()));
                    } else if ("tool_use".equals(type)) {
                        ObjectNode fc = objectMapper.createObjectNode();
                        fc.put("name", block.path("name").asText());
                        fc.set("args", block.path("input"));
                        parts.add(objectMapper.createObjectNode().set("functionCall", fc));
                    }
                }
                ObjectNode c = objectMapper.createObjectNode();
                c.put("role", "model");
                c.set("parts", parts);
                out.add(c);
                continue;
            }

            if ("user".equals(role) && content.isArray()) {
                ArrayNode parts = objectMapper.createArrayNode();
                for (JsonNode block : content) {
                    if ("tool_result".equals(block.path("type").asText())) {
                        String id = block.path("tool_use_id").asText();
                        String name = idToName.getOrDefault(id, id);
                        ObjectNode fr = objectMapper.createObjectNode();
                        fr.put("name", name);
                        fr.set("response", asResponseObject(block.path("content").asText("")));
                        parts.add(objectMapper.createObjectNode().set("functionResponse", fr));
                    }
                }
                ObjectNode c = objectMapper.createObjectNode();
                c.put("role", "user");
                c.set("parts", parts);
                out.add(c);
            }
        }
        return out;
    }

    private ObjectNode textContent(String role, String text) {
        ObjectNode c = objectMapper.createObjectNode();
        c.put("role", role);
        ArrayNode parts = objectMapper.createArrayNode();
        parts.add(objectMapper.createObjectNode().put("text", text));
        c.set("parts", parts);
        return c;
    }

    /** Gemini requires functionResponse.response to be a JSON object. */
    private JsonNode asResponseObject(String raw) {
        try {
            JsonNode parsed = objectMapper.readTree(raw);
            if (parsed.isObject()) return parsed;
            ObjectNode wrap = objectMapper.createObjectNode();
            wrap.set("result", parsed);
            return wrap;
        } catch (Exception e) {
            return objectMapper.createObjectNode().put("result", raw);
        }
    }

    private ArrayNode toGeminiTools(ArrayNode tools) {
        ArrayNode out = objectMapper.createArrayNode();
        if (tools == null || tools.size() == 0) return out;
        ArrayNode decls = objectMapper.createArrayNode();
        for (JsonNode t : tools) {
            ObjectNode decl = objectMapper.createObjectNode();
            decl.put("name", t.path("name").asText());
            decl.put("description", t.path("description").asText());
            JsonNode schema = t.path("input_schema");
            if (!schema.isMissingNode() && schema.isObject()) {
                decl.set("parameters", sanitizeSchema(schema));
            }
            decls.add(decl);
        }
        out.add(objectMapper.createObjectNode().set("function_declarations", decls));
        return out;
    }

    /** Keep only the JSON-Schema subset Gemini's function declarations accept. */
    private JsonNode sanitizeSchema(JsonNode schema) {
        if (!schema.isObject()) return schema;
        ObjectNode clean = objectMapper.createObjectNode();
        schema.fields().forEachRemaining(e -> {
            String k = e.getKey();
            if (!SCHEMA_KEYS.contains(k)) return;
            JsonNode v = e.getValue();
            if ("properties".equals(k) && v.isObject()) {
                ObjectNode props = objectMapper.createObjectNode();
                v.fields().forEachRemaining(p -> props.set(p.getKey(), sanitizeSchema(p.getValue())));
                clean.set(k, props);
            } else if ("items".equals(k)) {
                clean.set(k, sanitizeSchema(v));
            } else {
                clean.set(k, v);
            }
        });
        return clean;
    }

    // ── response translation: Gemini -> Anthropic dialect ───────────────

    private JsonNode fromGeminiResponse(JsonNode parsed) {
        JsonNode parts = parsed.path("candidates").path(0).path("content").path("parts");
        ArrayNode content = objectMapper.createArrayNode();
        boolean hasFunctionCall = false;
        int idx = 0;
        for (JsonNode part : parts) {
            if (part.has("functionCall")) {
                hasFunctionCall = true;
                JsonNode fc = part.path("functionCall");
                ObjectNode block = objectMapper.createObjectNode();
                block.put("type", "tool_use");
                block.put("id", "call_" + (idx++) + "_" + fc.path("name").asText());
                block.put("name", fc.path("name").asText());
                JsonNode args = fc.path("args");
                block.set("input", args.isMissingNode() || args.isNull()
                    ? objectMapper.createObjectNode() : args);
                content.add(block);
            } else if (part.has("text")) {
                ObjectNode block = objectMapper.createObjectNode();
                block.put("type", "text");
                block.put("text", part.path("text").asText());
                content.add(block);
            }
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.put("stop_reason", hasFunctionCall ? "tool_use" : "end_turn");
        out.set("content", content);
        return out;
    }
}
