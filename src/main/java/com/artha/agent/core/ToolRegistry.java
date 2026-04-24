package com.artha.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all Artha agent tools.
 *
 * At startup, scans the ApplicationContext for beans annotated
 * with @ArthaTool that implement FinancialTool. External plugins
 * registered via PluginLoader are also stored here.
 *
 * The orchestrator calls this registry instead of holding direct
 * tool references — adding a new tool requires zero orchestrator changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final ApplicationContext applicationContext;
    private final ObjectMapper       objectMapper;

    private final Map<String, FinancialTool> tools = new ConcurrentHashMap<>();

    @PostConstruct
    void discoverTools() {
        // Find all beans annotated with @ArthaTool
        Map<String, Object> annotatedBeans =
            applicationContext.getBeansWithAnnotation(ArthaTool.class);

        for (Map.Entry<String, Object> entry : annotatedBeans.entrySet()) {
            Object bean = entry.getValue();

            if (!(bean instanceof FinancialTool tool)) {
                log.warn("Bean '{}' has @ArthaTool but doesn't implement " +
                    "FinancialTool — skipping", entry.getKey());
                continue;
            }

            ArthaTool annotation = bean.getClass()
                .getAnnotation(ArthaTool.class);

            if (!annotation.enabled()) {
                log.info("Tool '{}' is disabled — skipping", tool.getName());
                continue;
            }

            register(tool);
        }

        log.info("ToolRegistry initialized — {} tools registered: {}",
            tools.size(), tools.keySet());
    }

    /** Register a tool manually (used by PluginLoader for external JARs). */
    public void register(FinancialTool tool) {
        tools.put(tool.getName(), tool);
        log.info("Tool registered: {} ({})", tool.getName(),
            tool.getClass().getSimpleName());
    }

    /** Look up a tool by name. Returns null if not found. */
    public FinancialTool getTool(String name) {
        return tools.get(name);
    }

    /** All registered tool names. */
    public Set<String> getRegisteredNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /** Build the tools array to send to the LLM. */
    public ArrayNode buildToolDefinitions() throws Exception {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        for (FinancialTool tool : tools.values()) {
            String toolJson = objectMapper.writeValueAsString(tool.getDefinition());
            toolsArray.add(objectMapper.readTree(toolJson));
        }
        return toolsArray;
    }

    /** Number of registered tools. */
    public int size() { return tools.size(); }
}