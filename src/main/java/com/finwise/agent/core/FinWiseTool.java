package com.finwise.agent.core;

import org.springframework.stereotype.Component;
import java.lang.annotation.*;

/**
 * Marks a class as a FinWise agent tool.
 * Any @Component implementing FinancialTool + annotated with @FinWiseTool
 * is auto-discovered by ToolRegistry at startup.
 *
 * Usage:
 *   @FinWiseTool
 *   @Component
 *   public class MyCustomTool implements FinancialTool { ... }
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface FinWiseTool {

    /** Human-readable description of what this tool does. */
    String description() default "";

    /** Tool category for grouping in the registry. */
    String category() default "general";

    /** Version string for this tool. */
    String version() default "1.0.0";

    /** If false, tool is registered but not sent to the LLM. */
    boolean enabled() default true;
}