package com.finwise.agent.controller;

import com.finwise.agent.core.AgentOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

/**
 * REST controller for the FinWise agent chat endpoint.
 *
 * POST /api/agent/chat
 * Body: { "userId": "uuid", "message": "How much did I spend on food?" }
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentOrchestrator orchestrator;

    @Value("${finwise.anthropic.api-key}")
    private String apiKey;

    @Value("${finwise.anthropic.model:claude-sonnet-4-6}")
    private String model;

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        if (request.userId() == null || request.userId().isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "userId is required"));
        }
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "message is required"));
        }

        log.info("Chat request — userId={}", request.userId());
        String response = orchestrator.chat(request.userId(), request.message());

        return ResponseEntity.ok(Map.of(
            "userId",   request.userId(),
            "message",  request.message(),
            "response", response
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "service", "finwise-agent"));
    }

    /** Temporary diagnostic endpoint — tests raw Anthropic API connectivity. */
    @GetMapping("/test-claude")
    public ResponseEntity<Map<String, Object>> testClaude() {
        String body = "{\"model\":\"" + model + "\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"Say OK\"}]}";
        try {
            String response = WebClient.builder()
                .baseUrl("https://api.anthropic.com/v1/messages")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build()
                .post()
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
            return ResponseEntity.ok(Map.of("status", "ok", "model", model, "response", response));
        } catch (WebClientResponseException e) {
            return ResponseEntity.ok(Map.of("status", "error", "httpStatus", e.getStatusCode().toString(),
                "body", e.getResponseBodyAsString(), "apiKey_prefix", apiKey.substring(0, Math.min(12, apiKey.length()))));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "error", "message", e.getMessage(),
                "apiKey_prefix", apiKey.substring(0, Math.min(12, apiKey.length()))));
        }
    }

    public record ChatRequest(String userId, String message) {}
}