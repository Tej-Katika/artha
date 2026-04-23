package com.finwise.agent.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Selects the active LLM provider based on configuration.
 *
 * application.properties:
 *   finwise.llm.provider=claude    (default)
 *   finwise.llm.provider=openai
 *   finwise.llm.provider=ollama
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMProviderFactory {

    @Value("${finwise.llm.provider:claude}")
    private String activeProvider;

    private final List<LLMProvider> providers;

    private Map<String, LLMProvider> providerMap;

    @jakarta.annotation.PostConstruct
    void init() {
        providerMap = providers.stream()
            .collect(Collectors.toMap(
                LLMProvider::getProviderName,
                p -> p
            ));
        log.info("LLM providers registered: {}", providerMap.keySet());
        log.info("Active LLM provider: {}", activeProvider);
    }

    public LLMProvider getProvider() {
        LLMProvider provider = providerMap.get(activeProvider);
        if (provider == null) {
            log.warn("Provider '{}' not found, falling back to claude", activeProvider);
            return providerMap.get("claude");
        }
        return provider;
    }

    public List<String> getAvailableProviders() {
        return List.copyOf(providerMap.keySet());
    }
}