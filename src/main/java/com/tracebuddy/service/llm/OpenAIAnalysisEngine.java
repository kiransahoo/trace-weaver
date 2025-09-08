package com.tracebuddy.service.llm;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContext;

import com.tracebuddy.model.TraceSpan;
import com.tracebuddy.model.PerformanceHotspot;
import com.tracebuddy.model.ErrorMethodInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * OpenAI implementation of AI Analysis Engine
 * @author kiransahoo
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "llm.engine.type", havingValue = "openai", matchIfMissing = true)
public class OpenAIAnalysisEngine implements AiAnalysisEngine {

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.url:https://api.openai.com/v1/chat/completions}")
    private String apiUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApplicationContext context;

    public OpenAIAnalysisEngine(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public String getEngineType() {
        return "OpenAI GPT-4";
    }

    @Override
    public String analyzeTraces(List<TraceSpan> traces, List<PerformanceHotspot> hotspots,
                                Map<String, Object> statistics) {
        try {
            String prompt = buildAnalysisPrompt(traces, hotspots, statistics);
            return callLLM(prompt);
        } catch (Exception e) {
            log.error("Error in OpenAI analysis", e);
            return "Unable to perform AI analysis: " + e.getMessage();
        }
    }

    @Override
    public String analyzeMethodPerformance(String methodName, TraceSpan slowTrace,
                                          List<TraceSpan> allTracesForMethod) {
        try {
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

            prompt.append("\nAnalyze and identify:\n");
            prompt.append("1. Likely causes of slowness\n");
            prompt.append("2. Database query issues (N+1 queries, missing indexes, etc.)\n");
            prompt.append("3. Algorithm complexity problems\n");
            prompt.append("4. I/O bottlenecks\n");
            prompt.append("5. Synchronization issues\n");
            prompt.append("6. Memory allocation patterns\n");
            prompt.append("\nProvide specific recommendations.\n");

            return callLLM(prompt.toString());
        } catch (Exception e) {
            log.error("Error analyzing method performance", e);
            return "Unable to analyze method: " + e.getMessage();
        }
    }

    @Override
    public String answerQuery(String userQuery, List<TraceSpan> traces,
                             List<PerformanceHotspot> hotspots) {
        try {
            String prompt = buildQueryPrompt(userQuery, traces, hotspots);
            return callLLM(prompt);
        } catch (Exception e) {
            log.error("Error answering query", e);
            return "Unable to process query: " + e.getMessage();
        }
    }

    @Override
    public String analyzeErrors(String userQuery, Map<String, ErrorMethodInfo> errorMethods,
                               List<TraceSpan> errorTraces) {
        try {
            String prompt = buildErrorAnalysisPrompt(userQuery, errorMethods, errorTraces);
            return callLLM(prompt);
        } catch (Exception e) {
            log.error("Error in AI error analysis", e);
            return "Unable to perform error analysis: " + e.getMessage();
        }
    }

    @Override
    public String analyzePerformance(String userQuery, List<TraceSpan> traces,
                                    Map<String, Object> performanceAnalysis) {
        try {
            String prompt = buildPerformanceAnalysisPrompt(userQuery, traces, performanceAnalysis);
            return callLLM(prompt);
        } catch (Exception e) {
            log.error("Error in AI performance analysis", e);
            return "Unable to perform performance analysis: " + e.getMessage();
        }
    }

    private String buildAnalysisPrompt(List<TraceSpan> traces,
                                       List<PerformanceHotspot> hotspots,
                                       Map<String, Object> statistics) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze the following trace data and provide insights:\n\n");

        prompt.append("Overall Statistics:\n");
        statistics.forEach((key, value) ->
                prompt.append(String.format("- %s: %s\n", key, value))
        );

        // Show actual traces in the data
        prompt.append("\nActual Traces Found (grouped by operation):\n");
        Map<String, List<TraceSpan>> groupedTraces = traces.stream()
                .collect(Collectors.groupingBy(TraceSpan::getOperationName));

        groupedTraces.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                .limit(20)
                .forEach(entry -> {
                    String operation = entry.getKey();
                    List<TraceSpan> opTraces = entry.getValue();
                    double avgDuration = opTraces.stream()
                            .mapToDouble(TraceSpan::getDurationMs)
                            .average()
                            .orElse(0);
                    double maxDuration = opTraces.stream()
                            .mapToDouble(TraceSpan::getDurationMs)
                            .max()
                            .orElse(0);

                    prompt.append(String.format("- %s: %d occurrences, avg %.2fms, max %.2fms\n",
                            operation, opTraces.size(), avgDuration, maxDuration));
                });

        prompt.append("\nTop Performance Hotspots:\n");
        if (hotspots.isEmpty()) {
            prompt.append("No hotspots detected by the detection algorithm.\n");
        } else {
            hotspots.stream().limit(10).forEach(h -> {
                prompt.append(String.format("- Operation: %s, Avg Duration: %.2fms, " +
                                "Max Duration: %.2fms, Error Rate: %.2f%%, Occurrences: %d\n",
                        h.getOperation(), h.getAvgDurationMs(), h.getMaxDurationMs(),
                        h.getErrorRate() * 100, h.getOccurrenceCount()));
            });
        }

        prompt.append("\nProvide:\n");
        prompt.append("1. Summary of the actual operations found\n");
        prompt.append("2. Key performance issues based on the real data\n");
        prompt.append("3. Specific optimization recommendations for the actual methods\n");
        prompt.append("4. Priority order for addressing issues\n");

        return prompt.toString();
    }

    private String buildQueryPrompt(String userQuery, List<TraceSpan> traces,
                                   List<PerformanceHotspot> hotspots) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("User Query: ").append(userQuery).append("\n\n");

        prompt.append("Available trace data:\n");
        prompt.append(String.format("- Total traces: %d\n", traces.size()));
        prompt.append(String.format("- Total hotspots detected: %d\n", hotspots.size()));

        if (userQuery.toLowerCase().contains("hotspot") ||
                userQuery.toLowerCase().contains("slow") ||
                userQuery.toLowerCase().contains("bottleneck")) {
            
            if (hotspots.isEmpty()) {
                prompt.append("\nNo performance hotspots were detected in the analyzed traces.\n");
            } else {
                prompt.append("\nHere are the actual hotspots found:\n\n");
                for (PerformanceHotspot h : hotspots) {
                    if ("HIGH".equals(h.getSeverity())) {
                        prompt.append(String.format("CRITICAL HOTSPOT: %s\n", h.getOperation()));
                        prompt.append(String.format("  - Average Duration: %.2fms\n", h.getAvgDurationMs()));
                        prompt.append(String.format("  - Max Duration: %.2fms\n", h.getMaxDurationMs()));
                        prompt.append(String.format("  - Called %d times\n", h.getOccurrenceCount()));
                        if (h.getErrorRate() > 0) {
                            prompt.append(String.format("  - Error Rate: %.1f%%\n", h.getErrorRate() * 100));
                        }
                        prompt.append("\n");
                    }
                }
            }
        }

        prompt.append("\nAnswer based on the actual data above. Be specific about method names and durations.");
        
        return prompt.toString();
    }

    private String buildErrorAnalysisPrompt(String userQuery,
                                           Map<String, ErrorMethodInfo> errorMethods,
                                           List<TraceSpan> errorTraces) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze the following error data:\n\n");
        prompt.append("User Query: ").append(userQuery).append("\n\n");

        prompt.append("Methods with Errors:\n");
        errorMethods.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().getErrorCount(), e1.getValue().getErrorCount()))
                .limit(10)
                .forEach(entry -> {
                    ErrorMethodInfo info = entry.getValue();
                    prompt.append(String.format("- %s: %d errors, Avg Duration: %.2fms\n",
                            info.getMethodName(), info.getErrorCount(), info.getAvgDuration()));
                });

        prompt.append("\nProvide:\n");
        prompt.append("1. Root cause analysis of the most common errors\n");
        prompt.append("2. Pattern identification across error types\n");
        prompt.append("3. Specific recommendations to fix each error type\n");
        prompt.append("4. Priority order for addressing these errors\n");

        return prompt.toString();
    }

    private String buildPerformanceAnalysisPrompt(String userQuery, List<TraceSpan> traces,
                                                  Map<String, Object> performanceAnalysis) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze the following performance data:\n\n");
        prompt.append("User Query: ").append(userQuery).append("\n\n");

        @SuppressWarnings("unchecked")
        Map<String, Object> overallMetrics = (Map<String, Object>) performanceAnalysis.get("overallMetrics");
        if (overallMetrics != null) {
            prompt.append("Overall Performance Metrics:\n");
            prompt.append(String.format("- Total Traces: %d\n", overallMetrics.get("totalTraces")));
            prompt.append(String.format("- Average Duration: %.2fms\n", overallMetrics.get("avgDuration")));
            prompt.append(String.format("- Max Duration: %.2fms\n", overallMetrics.get("maxDuration")));
        }

        prompt.append("\nProvide:\n");
        prompt.append("1. Key performance bottlenecks identified\n");
        prompt.append("2. Root cause analysis for slow operations\n");
        prompt.append("3. Specific optimization recommendations\n");
        prompt.append("4. Expected impact of suggested optimizations\n");

        return prompt.toString();
    }

    private String callLLM(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-4");
        requestBody.put("messages", Arrays.asList(
                Map.of("role", "system", "content",
                        "You are an expert in application performance monitoring and distributed tracing. " +
                                "Provide detailed, technical analysis with specific recommendations."),
                Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 1000);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    apiUrl,
                    HttpMethod.POST,
                    request,
                    Map.class
            );

            Map<String, Object> responseBody = response.getBody();
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");

            return (String) message.get("content");
        } catch (Exception e) {
            log.error("Error calling OpenAI API", e);
            throw new RuntimeException("OpenAI API call failed: " + e.getMessage());
        }
    }
}
