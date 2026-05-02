package com.artha.core.action;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain-scoped registry of Actions, discovered at startup by Spring
 * type-scanning the ApplicationContext.
 *
 * Mirrors the discovery pattern used by ToolRegistry for v1 tools, so
 * adding a new Action requires zero registry-side changes — implement
 * Action&lt;I, O&gt;, annotate as a @Component, restart.
 *
 * Lookup is by (domain, actionName).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionRegistry {

    private final ApplicationContext applicationContext;

    /** Map key: "domain::actionName" — keeps the same name reusable across domains. */
    private final Map<String, Action<?, ?>> actions = new ConcurrentHashMap<>();

    @PostConstruct
    @SuppressWarnings({"unchecked", "rawtypes"})
    void discoverActions() {
        Map<String, Action> beans = applicationContext.getBeansOfType(Action.class);
        for (Map.Entry<String, Action> entry : beans.entrySet()) {
            Action<?, ?> action = entry.getValue();
            register(action);
        }
        log.info("ActionRegistry initialized — {} actions registered: {}",
            actions.size(), actions.keySet());
    }

    public void register(Action<?, ?> action) {
        String key = key(action.domain(), action.name());
        Action<?, ?> previous = actions.put(key, action);
        if (previous != null && previous != action) {
            log.warn("Action key collision: {} — replacing {} with {}",
                key, previous.getClass().getSimpleName(),
                action.getClass().getSimpleName());
        }
        log.info("Action registered: {} ({})", key, action.getClass().getSimpleName());
    }

    /** Look up by (domain, actionName). Null if not registered. */
    public Action<?, ?> get(String domain, String actionName) {
        return actions.get(key(domain, actionName));
    }

    public Set<String> registeredKeys() {
        return Collections.unmodifiableSet(actions.keySet());
    }

    public int size() { return actions.size(); }

    private static String key(String domain, String name) {
        return domain + "::" + name;
    }
}
