package com.artha.core.constraint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain-scoped registry of Constraints, discovered at startup.
 *
 * Mirrors {@link com.artha.core.action.ActionRegistry} discovery
 * pattern. Constraints are looked up by domain — the orchestrator
 * runs all registered constraints for the active domain on every
 * candidate response.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConstraintRegistry {

    private final ApplicationContext applicationContext;

    private final Map<String, List<Constraint>> byDomain = new ConcurrentHashMap<>();

    @PostConstruct
    void discover() {
        Map<String, Constraint> beans = applicationContext.getBeansOfType(Constraint.class);
        for (Constraint c : beans.values()) {
            register(c);
        }
        for (Map.Entry<String, List<Constraint>> e : byDomain.entrySet()) {
            List<String> names = new ArrayList<>();
            for (Constraint c : e.getValue()) names.add(c.name());
            log.info("ConstraintRegistry — domain {}: {}", e.getKey(), names);
        }
    }

    public void register(Constraint c) {
        byDomain
            .computeIfAbsent(c.domain(), k -> new ArrayList<>())
            .add(c);
    }

    /** All constraints active for `domain`. Empty list if none. */
    public List<Constraint> forDomain(String domain) {
        return Collections.unmodifiableList(
            byDomain.getOrDefault(domain, List.of()));
    }

    public int size() {
        int total = 0;
        for (List<Constraint> v : byDomain.values()) total += v.size();
        return total;
    }
}
