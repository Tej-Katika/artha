package com.finwise.agent.llm;

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

@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeProvider implements LLMProvider {

    @Value("${finwise.anthropic.api-key}")
    private String apiKey;

    @Value("${finwise.anthropic.model:claude-sonnet-4-5-20250929}")
    private String model;

    private final ObjectMapper objectMapper;

    private static final String ANTHROPIC_URL     = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Override
    public String getProviderName() { return "claude"; }

    @Override
    public boolean supportsToolCalling() { return true; }

    @Override
    public String complete(ArrayNode messages, ArrayNode tools, String systemPrompt)
            throws Exception {

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model",      model);
        body.put("max_tokens", 2048);
        body.put("system",     systemPrompt);
        body.set("messages",   messages);
        body.set("tools",      tools);

        String bodyJson = objectMapper.writeValueAsString(body);
        log.debug("[Claude] Request: {}", bodyJson);

        try {
            return WebClient.create()
                .post()
                .uri(ANTHROPIC_URL)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("x-api-key",         apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .bodyValue(bodyJson)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        } catch (WebClientResponseException e) {
            log.error("[Claude] API error {}: {}", e.getStatusCode(),
                e.getResponseBodyAsString());
            throw e;
        }
    }
}