package com.tracebuddy.service.llm;

import com.tracebuddy.model.PerformanceHotspot;
import com.tracebuddy.model.TraceSpan;
import com.tracebuddy.model.ErrorMethodInfo;
import com.tracebuddy.service.llm.AiAnalysisEngine;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import com.tracebuddy.config.McpProperties;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM Analysis of hotspots using MCP - now modular
 * @author kiransahoo
 */
@Service
@Slf4j
public class LLMAnalysisService {

    @Autowired(required = false)
    private AiAnalysisEngine aiEngine;

    @Autowired
    private ApplicationContext context;

    @Autowired(required = false)
    private McpSyncClient mcpClient;

    @Autowired(required = false)
    private McpProperties mcpProperties;

    @Value("${llm.engine.type:openai}")
    private String engineType;

    // Use the AI engine if configured, otherwise fall back to default implementation
    private AiAnalysisEngine getEngine() {
        if (aiEngine != null) {
            return aiEngine;
        }
        // Fall back to default OpenAI implementation
        return getDefaultEngine();
    }

    private AiAnalysisEngine getDefaultEngine() {
        // Return default OpenAI implementation
        return new OpenAIAnalysisEngine(context);
    }

    private boolean isMcpAvailable() {
        try {
            if (mcpProperties == null || !mcpProperties.isEnabled()) {
                return false;
            }

            // For GitHub, just check if githubTools bean exists
            if ("github".equals(mcpProperties.getSource())) {
                return context.containsBean("vcsTools");
            }

            // For filesystem, check the original way
            context.getBean(ChatClient.Builder.class);
            context.getBean(McpSyncClient.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String analyzeTraces(List<TraceSpan> traces, List<PerformanceHotspot> hotspots,
                                Map<String, Object> statistics) {
        try {
            if (isMcpAvailable()) {
                // Use MCP-enhanced analysis
                String prompt = buildAnalysisPromptWithCode(traces, hotspots, statistics);
                return callLLMWithMcp(prompt);
            } else {
                // Use the modular AI engine
                return getEngine().analyzeTraces(traces, hotspots, statistics);
            }
        } catch (Exception e) {
            log.error("Error in LLM analysis", e);
            return "Unable to perform LLM analysis: " + e.getMessage();
        }
    }

    public String analyzeMethodPerformance(String methodName, TraceSpan slowTrace,
                                           List<TraceSpan> allTracesForMethod) {
        try {
            if (isMcpAvailable()) {
                String prompt = buildMethodAnalysisPromptWithCode(methodName, slowTrace, allTracesForMethod);
                return callLLMWithMcp(prompt);
            } else {
                return getEngine().analyzeMethodPerformance(methodName, slowTrace, allTracesForMethod);
            }
        } catch (Exception e) {
            log.error("Error analyzing method performance", e);
            return "Unable to analyze method: " + e.getMessage();
        }
    }

    public String answerQuery(String userQuery, List<TraceSpan> traces,
                              List<PerformanceHotspot> hotspots) {
        try {
            return getEngine().answerQuery(userQuery, traces, hotspots);
        } catch (Exception e) {
            log.error("Error answering query", e);
            return "Unable to process query: " + e.getMessage();
        }
    }

    public String analyzeErrors(String userQuery, Map<String, ErrorMethodInfo> errorMethods,
                                List<TraceSpan> errorTraces) {
        try {
            return getEngine().analyzeErrors(userQuery, errorMethods, errorTraces);
        } catch (Exception e) {
            log.error("Error in LLM error analysis", e);
            return "Unable to perform error analysis: " + e.getMessage();
        }
    }

    public String analyzePerformance(String userQuery, List<TraceSpan> traces,
                                     Map<String, Object> performanceAnalysis) {
        try {
            return getEngine().analyzePerformance(userQuery, traces, performanceAnalysis);
        } catch (Exception e) {
            log.error("Error in LLM performance analysis", e);
            return "Unable to perform performance analysis: " + e.getMessage();
        }
    }

    private String buildAnalysisPromptWithCode(List<TraceSpan> traces,
                                               List<PerformanceHotspot> hotspots,
                                               Map<String, Object> statistics) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze the following trace data. You have access to the application source code through MCP tools.\n\n");

        // Include existing statistics
        prompt.append("Overall Statistics:\n");
        statistics.forEach((key, value) ->
                prompt.append(String.format("- %s: %s\n", key, value))
        );

        prompt.append("\nTop Performance Hotspots:\n");
        hotspots.stream().limit(5).forEach(h -> {
            prompt.append(String.format("\n=== HOTSPOT: %s ===\n", h.getOperation()));
            prompt.append(String.format("Performance: Avg %.2fms, Max %.2fms, Error Rate: %.2f%%, Count: %d\n",
                    h.getAvgDurationMs(), h.getMaxDurationMs(),
                    h.getErrorRate() * 100, h.getOccurrenceCount()));

            // Extract class name from operation
            String className = extractClassName(h.getOperation());
            if (className != null) {
                if ("github".equals(mcpProperties.getSource())) {
                    prompt.append(String.format("\nUse the 'readJavaFileByClassName' tool to read the source code for class: %s\n", className));
                } else if ("filesystem".equals(mcpProperties.getSource())) {
                    String basePath = mcpProperties.getBasePath();
                    String javaPath = mcpProperties.getSourcePaths().get("java");
                    if (javaPath == null) {
                        javaPath = "src/main/java";
                    }
                    String fullPath = basePath + "/" + javaPath + "/" + className.replace('.', '/') + ".java";
                    prompt.append(String.format("\nPlease read the source code from file: %s\n", fullPath));
                }

                log.debug("Requesting source code for class: {}", className);
            }
        });

        prompt.append("\nBased on the performance data AND source code analysis, provide:\n");
        prompt.append("1. Specific code-level issues causing performance problems\n");
        prompt.append("2. Exact lines or patterns in the code that need optimization\n");
        prompt.append("3. Concrete refactoring suggestions with code examples\n");
        prompt.append("4. Identify any anti-patterns (N+1 queries, nested loops, etc.)\n");
        prompt.append("5. Suggest caching strategies based on the actual code logic\n");
        prompt.append("6. Priority order for fixes based on impact and complexity\n");

        return prompt.toString();
    }

    private String buildMethodAnalysisPromptWithCode(String methodName, TraceSpan slowTrace,
                                                     List<TraceSpan> allTracesForMethod) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze why this specific method is slow:\n\n");
        prompt.append("Method: ").append(methodName).append("\n");

        if (slowTrace != null) {
            prompt.append("Slowest execution: ").append(slowTrace.getDurationMs()).append("ms\n");
        }

        if (allTracesForMethod != null && !allTracesForMethod.isEmpty()) {
            prompt.append("Average duration: ").append(
                    allTracesForMethod.stream()
                            .mapToDouble(TraceSpan::getDurationMs)
                            .average()
                            .orElse(0)
            ).append("ms\n\n");
        }

        // Extract class name
        String className = extractClassName(methodName);
        if (className != null) {
            prompt.append("Use the 'readJavaFileByClassName' tool to find and read the source code for class: ")
                    .append(className).append("\n");
            prompt.append("The tool will search the repository and find the correct file.\n");

            log.debug("Requesting source code for class: {}", className);
        }

        prompt.append("\nAfter reading the source code, analyze and identify:\n");
        prompt.append("1. Specific lines or methods causing slowness\n");
        prompt.append("2. Database query issues (N+1 queries, missing indexes, etc.)\n");
        prompt.append("3. Algorithm complexity problems (nested loops, inefficient searches)\n");
        prompt.append("4. I/O bottlenecks (file operations, network calls)\n");
        prompt.append("5. Synchronization issues or blocking operations\n");
        prompt.append("6. Memory allocation patterns that could cause GC pressure\n");
        prompt.append("\nProvide specific code-level recommendations with examples.\n");

        return prompt.toString();
    }

    private String callLLMWithMcp(String prompt) {
        try {
            // Get ChatClient builder
            ChatClient.Builder chatClientBuilder = context.getBean(ChatClient.Builder.class);

            // Check which source is configured
            String source = mcpProperties.getSource();

            if ("github".equals(source)) {
                // Use VCS tools
                var vcsTools = context.getBean("vcsTools", ToolCallbackProvider.class);
                var chatClient = chatClientBuilder
                        .defaultToolCallbacks(vcsTools)
                        .build();
                log.debug("Using VCS tools for analysis");
                return chatClient.prompt(prompt).call().content();
            } else {
                // Filesystem mode - original code
                McpSyncClient mcpClient = context.getBean(McpSyncClient.class);
                var chatClient = chatClientBuilder
                        .defaultToolCallbacks(new SyncMcpToolCallbackProvider(mcpClient))
                        .build();
                return chatClient.prompt(prompt).call().content();
            }

        } catch (Exception e) {
            log.warn("Failed to use MCP-enhanced LLM, falling back to regular LLM", e);
            return getEngine().analyzeTraces(null, null, null);
        }
    }

    // Helper methods
    private String extractClassName(String operationName) {
        if (operationName == null || operationName.isEmpty()) {
            return null;
        }

        // Remove CGLIB proxy suffixes first
        String normalized = operationName;
        if (normalized.contains("$$EnhancerBySpringCGLIB$$")) {
            normalized = normalized.substring(0, normalized.indexOf("$$EnhancerBySpringCGLIB$$"));
        }
        if (normalized.contains("CGLIB$")) {
            int cglibIndex = normalized.indexOf("CGLIB$");
            if (cglibIndex > 0) {
                normalized = normalized.substring(0, cglibIndex - 1);
            }
        }

        // Now extract the class name (remove method if present)
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot > 0) {
            String possibleMethod = normalized.substring(lastDot + 1);
            // If it starts with lowercase or contains parentheses, it's likely a method name
            if (!possibleMethod.isEmpty() &&
                    (Character.isLowerCase(possibleMethod.charAt(0)) || possibleMethod.contains("("))) {
                // Remove the method part
                normalized = normalized.substring(0, lastDot);
            }
        }

        log.debug("Extracted class name: {} from operation: {}", normalized, operationName);
        return normalized;
    }

    private String extractMethodName(String operationName) {
        if (operationName == null || operationName.isEmpty()) {
            return operationName;
        }

        int lastDot = operationName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < operationName.length() - 1) {
            String possibleMethod = operationName.substring(lastDot + 1);
            // If it starts with lowercase, it's likely a method name
            if (!possibleMethod.isEmpty() && Character.isLowerCase(possibleMethod.charAt(0))) {
                return possibleMethod;
            }
        }

        return operationName;
    }

    private String extractPackageName(String operationName) {
        String className = extractClassName(operationName);
        if (className == null) {
            return null;
        }

        int lastDot = className.lastIndexOf('.');
        if (lastDot > 0) {
            return className.substring(0, lastDot);
        }

        return null;
    }
}
