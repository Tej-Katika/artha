package com.finwise.agent.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Scans the plugins/ directory for JAR files at startup.
 * Any JAR containing a service entry for FinancialTool
 * (via META-INF/services/com.finwise.agent.core.FinancialTool)
 * is loaded and registered automatically.
 *
 * Plugin JAR structure:
 *   my-plugin.jar
 *   ├── com/example/MyCryptoTool.class
 *   └── META-INF/services/
 *       └── com.finwise.agent.core.FinancialTool   ← "com.example.MyCryptoTool"
 *
 * Drop the JAR into the plugins/ folder and restart — zero code changes needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginLoader {

    @Value("${finwise.plugins.directory:plugins}")
    private String pluginsDirectory;

    @Value("${finwise.plugins.enabled:true}")
    private boolean pluginsEnabled;

    private final ToolRegistry toolRegistry;

    @EventListener(ApplicationReadyEvent.class)
    public void loadPlugins() {
        if (!pluginsEnabled) {
            log.info("Plugin loading disabled (finwise.plugins.enabled=false)");
            return;
        }

        File pluginDir = new File(pluginsDirectory);
        if (!pluginDir.exists() || !pluginDir.isDirectory()) {
            log.info("No plugins directory found at '{}' — skipping plugin load",
                pluginsDirectory);
            return;
        }

        File[] jars = pluginDir.listFiles(
            f -> f.isFile() && f.getName().endsWith(".jar"));

        if (jars == null || jars.length == 0) {
            log.info("No plugin JARs found in '{}'", pluginsDirectory);
            return;
        }

        log.info("Found {} plugin JAR(s) in '{}'", jars.length, pluginsDirectory);

        List<FinancialTool> loaded = new ArrayList<>();

        for (File jar : jars) {
            try {
                URL jarUrl = jar.toURI().toURL();
                URLClassLoader classLoader = new URLClassLoader(
                    new URL[]{ jarUrl },
                    getClass().getClassLoader()
                );

                ServiceLoader<FinancialTool> serviceLoader =
                    ServiceLoader.load(FinancialTool.class, classLoader);

                for (FinancialTool tool : serviceLoader) {
                    toolRegistry.register(tool);
                    loaded.add(tool);
                    log.info("Plugin tool loaded: {} from {}",
                        tool.getName(), jar.getName());
                }

            } catch (Exception e) {
                log.error("Failed to load plugin JAR '{}': {}",
                    jar.getName(), e.getMessage());
            }
        }

        log.info("Plugin loading complete — {} tool(s) loaded from {} JAR(s)",
            loaded.size(), jars.length);
    }
}