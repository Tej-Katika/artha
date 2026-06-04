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
 * Anthropic Messages API transport. This is the original, default agent
 * backbone (Claude Sonnet 4.6); its request/response are already in the
 * orchestrator's internal dialect, so no translation is needed. The logic
 * is preserved verbatim from the orchestrator's former {@code callClaude}
 * so the production path is behavior-identical.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicTransport implements LlmTransport {

    @Value("${artha.anthropic.api-key}")
    private String apiKey;

    @Value("${artha.anthropic.model:claude-haiku-4-5-20251001}")
    private String model;

    private final ObjectMapper objectMapper;

    private static final String API_URL     = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final int    UPSTREAM_MAX_ATTEMPTS    = 4;
    private static final long   UPSTREAM_BACKOFF_BASE_MS = 2000L;

    @Override
    public String provider() { return "anthropic"; }

    @Override
    public JsonNode call(ArrayNode messages, ArrayNode tools, String system) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model",      model);
        body.put("max_tokens", 1024);
        body.put("system",     system);
        body.set("messages",   messages);
        body.set("tools",      tools);
        String bodyStr = body.toString();

        for (int attempt = 1; attempt <= UPSTREAM_MAX_ATTEMPTS; attempt++) {
            try {
                String responseBody = WebClient.builder()
                    .baseUrl(API_URL)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .defaultHeader("x-api-key",         apiKey)
                    .defaultHeader("anthropic-version", API_VERSION)
                    .build()
                    .post()
                    .bodyValue(bodyStr)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

                JsonNode parsed = objectMapper.readTree(responseBody);
                logUsage(parsed.path("usage"));
                return parsed;

            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = (status == 529 || status == 429)
                                    && attempt < UPSTREAM_MAX_ATTEMPTS;
                if (retryable) {
                    long waitMs = UPSTREAM_BACKOFF_BASE_MS * (1L << (attempt - 1));
                    log.warn("Claude API {} on attempt {}/{} — retrying in {}ms",
                            status, attempt, UPSTREAM_MAX_ATTEMPTS, waitMs);
                    try { Thread.sleep(waitMs); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                log.error("Claude API error — status={} body={}",
                        e.getStatusCode(), e.getResponseBodyAsString());
                return null;
            } catch (Exception e) {
                log.error("Claude call failed — {}", e.getMessage(), e);
                return null;
            }
        }
        return null;
    }

    private void logUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) return;
        long input       = usage.path("input_tokens").asLong(0);
        long cacheCreate = usage.path("cache_creation_input_tokens").asLong(0);
        long cacheRead   = usage.path("cache_read_input_tokens").asLong(0);
        long output      = usage.path("output_tokens").asLong(0);
        if (cacheCreate + cacheRead == 0) {
            log.debug("Anthropic usage — input={} output={}", input, output);
        } else {
            log.info("Anthropic usage — input={} cache_create={} cache_read={} output={}",
                input, cacheCreate, cacheRead, output);
        }
    }
}
