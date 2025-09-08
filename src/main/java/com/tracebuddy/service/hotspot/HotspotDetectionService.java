package com.tracebuddy.service.hotspot;

import com.tracebuddy.model.PerformanceHotspot;
import com.tracebuddy.model.TraceSpan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hotspot detection service
 * @author kiransahoo
 */
@Slf4j
@Service
public class HotspotDetectionService {

    // Pattern to extract class and method from Java operation names
    private static final Pattern JAVA_METHOD_PATTERN = Pattern.compile(
            "^(?<package>[a-zA-Z0-9.]+\\.)?(?<class>[A-Za-z0-9$]+)(\\.(?<method>[a-zA-Z0-9_$]+))?$"
    );

    // Pattern for Spring CGLIB proxies
    private static final Pattern CGLIB_PATTERN = Pattern.compile(
            "^(.+)\\$\\$EnhancerBySpringCGLIB\\$\\$[a-f0-9]+$"
    );

    public List<PerformanceHotspot> detectHotspots(List<TraceSpan> traces) {
        // Group by normalized operation name to handle CGLIB proxies
        Map<String, List<TraceSpan>> groupedByOperation = traces.stream()
                .collect(Collectors.groupingBy(this::normalizeOperationName));

        List<PerformanceHotspot> hotspots = new ArrayList<>();

        for (Map.Entry<String, List<TraceSpan>> entry : groupedByOperation.entrySet()) {
            String operation = entry.getKey();
            List<TraceSpan> operationTraces = entry.getValue();

            if (operationTraces.size() < 2) continue; // Lower threshold for App Insights data

            double avgDuration = operationTraces.stream()
                    .mapToDouble(TraceSpan::getDurationMs)
                    .average()
                    .orElse(0);

            double maxDuration = operationTraces.stream()
                    .mapToDouble(TraceSpan::getDurationMs)
                    .max()
                    .orElse(0);

            long errorCount = operationTraces.stream()
                    .filter(t -> !isSuccessful(t))
                    .count();

            double errorRate = (double) errorCount / operationTraces.size();

            String severity = determineSeverity(avgDuration, maxDuration, errorRate);

            // Extract class and method info
            MethodInfo methodInfo = extractMethodInfo(operation);

            List<String> relatedOps = findRelatedOperations(operation, traces);
            List<String> recommendations = generateRecommendations(
                    operation, avgDuration, maxDuration, errorRate, methodInfo
            );

            // Build detailed operation description
            String detailedOperation = buildDetailedOperation(operation, methodInfo);

            PerformanceHotspot hotspot = PerformanceHotspot.builder()
                    .operation(detailedOperation)
                    .avgDurationMs(avgDuration)
                    .maxDurationMs(maxDuration)
                    .occurrenceCount(operationTraces.size())
                    .errorRate(errorRate)
                    .relatedOperations(relatedOps)
                    .recommendations(recommendations)
                    .severity(severity)
                    .build();

            hotspots.add(hotspot);
        }

        return hotspots.stream()
                .sorted((h1, h2) -> {
                    int severityCompare = getSeverityWeight(h2.getSeverity()) -
                            getSeverityWeight(h1.getSeverity());
                    if (severityCompare != 0) return severityCompare;
                    return Double.compare(h2.getAvgDurationMs(), h1.getAvgDurationMs());
                })
                .collect(Collectors.toList());
    }

    // Find the slowest individual traces
    public List<TraceSpan> findSlowestTraces(List<TraceSpan> traces, int limit) {
        return traces.stream()
                .sorted((t1, t2) -> Double.compare(t2.getDurationMs(), t1.getDurationMs()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    // Find traces that form a complete call chain
    public Map<String, List<TraceSpan>> findCallChains(List<TraceSpan> traces) {
        Map<String, List<TraceSpan>> chains = new HashMap<>();

        // Group by trace ID to find complete call chains
        Map<String, List<TraceSpan>> byTraceId = traces.stream()
                .filter(t -> t.getTraceId() != null && !t.getTraceId().isEmpty())
                .collect(Collectors.groupingBy(TraceSpan::getTraceId));

        for (Map.Entry<String, List<TraceSpan>> entry : byTraceId.entrySet()) {
            String traceId = entry.getKey();
            List<TraceSpan> chain = entry.getValue();

            // Sort by parent-child relationship
            List<TraceSpan> sortedChain = sortCallChain(chain);
            chains.put(traceId, sortedChain);
        }

        return chains;
    }

    private String normalizeOperationName(TraceSpan trace) {
        String operation = trace.getOperationName();

        // Remove CGLIB proxy suffixes
        Matcher cglibMatcher = CGLIB_PATTERN.matcher(operation);
        if (cglibMatcher.matches()) {
            operation = cglibMatcher.group(1);
        }

        // Remove CGLIB method prefixes
        if (operation.contains("CGLIB$")) {
            return operation.substring(0, operation.indexOf("CGLIB$") - 1);
        }

        return operation;
    }

    private boolean isSuccessful(TraceSpan trace) {
        String status = trace.getStatus();
        if (status == null || status.isEmpty()) {
            // Check Success attribute for App Insights
            Object success = trace.getAttributes().get("success");
            return success == null || "True".equalsIgnoreCase(success.toString());
        }
        return status.startsWith("2") || status.equals("0");
    }

    private static class MethodInfo {
        String packageName;
        String className;
        String methodName;
        String fullClassName;

        boolean isValid() {
            return className != null && !className.isEmpty();
        }
    }

    private MethodInfo extractMethodInfo(String operation) {
        MethodInfo info = new MethodInfo();

        // Handle HTTP operations
        if (operation.startsWith("GET ") || operation.startsWith("POST ") ||
                operation.startsWith("PUT ") || operation.startsWith("DELETE ")) {
            info.className = "HTTP";
            info.methodName = operation;
            return info;
        }

        // Try to parse Java method format
        Matcher matcher = JAVA_METHOD_PATTERN.matcher(operation);
        if (matcher.matches()) {
            String packagePart = matcher.group("package");
            info.className = matcher.group("class");
            info.methodName = matcher.group("method");

            if (packagePart != null && !packagePart.isEmpty()) {
                info.packageName = packagePart.substring(0, packagePart.length() - 1); // Remove trailing dot
                info.fullClassName = info.packageName + "." + info.className;
            } else {
                info.fullClassName = info.className;
            }

            // Handle inner classes
            if (info.className != null && info.className.contains("$")) {
                String[] parts = info.className.split("\\$");
                info.className = parts[parts.length - 1]; // Get the inner class name
            }
        } else {
            // Fallback: use the operation name as-is
            info.methodName = operation;
        }

        return info;
    }

    private String buildDetailedOperation(String operation, MethodInfo info) {
        if (!info.isValid()) {
            return operation;
        }

        StringBuilder detailed = new StringBuilder();

        // Add class info
        if (info.fullClassName != null) {
            detailed.append(info.fullClassName);
        } else if (info.className != null) {
            detailed.append(info.className);
        }

        // Add method info
        if (info.methodName != null && !info.methodName.isEmpty()) {
            if (detailed.length() > 0) {
                detailed.append(".");
            }
            detailed.append(info.methodName);
        }

        // Add original operation in parentheses if different
        String built = detailed.toString();
        if (!built.equals(operation) && !operation.contains("CGLIB")) {
            detailed.append(" (").append(operation).append(")");
        }

        return detailed.toString();
    }

    private List<String> generateRecommendations(String operation, double avgDuration,
                                                 double maxDuration, double errorRate,
                                                 MethodInfo methodInfo) {
        List<String> recommendations = new ArrayList<>();

        // Performance-based recommendations
        if (avgDuration > 5000) {
            recommendations.add("Critical performance issue - operation taking > 5 seconds on average");
            if (methodInfo.isValid()) {
                recommendations.add(String.format("Profile method %s.%s to identify bottlenecks",
                        methodInfo.className, methodInfo.methodName));
            }
        } else if (avgDuration > 2000) {
            recommendations.add("Significant delay detected - consider optimization");
        }

        // Variance-based recommendations
        if (maxDuration > avgDuration * 3) {
            recommendations.add("High variance in execution time - investigate environmental factors");
            recommendations.add("Consider implementing timeout and retry mechanisms");
        }

        // Error-based recommendations
        if (errorRate > 0.1) {
            recommendations.add(String.format("High error rate (%.1f%%) - review error handling",
                    errorRate * 100));
        }

        // Method-specific recommendations
        if (methodInfo.isValid()) {
            if (methodInfo.methodName != null) {
                if (methodInfo.methodName.toLowerCase().contains("database") ||
                        methodInfo.methodName.toLowerCase().contains("repository") ||
                        methodInfo.methodName.toLowerCase().contains("findall")) {
                    recommendations.add("Database operation detected - check query performance and indexes");
                    recommendations.add("Consider implementing pagination for findAll operations");
                }

                if (methodInfo.methodName.equals("main")) {
                    recommendations.add("Application startup is slow - review initialization logic");
                    recommendations.add("Consider lazy loading of components");
                }

                if (operation.contains("restTemplate")) {
                    recommendations.add("HTTP client operation - check network latency and timeouts");
                    recommendations.add("Consider implementing connection pooling");
                }
            }
        }

        // HTTP-specific recommendations
        if (operation.startsWith("GET ") || operation.startsWith("POST ")) {
            recommendations.add("HTTP endpoint - consider caching for GET requests");
            recommendations.add("Monitor external service dependencies");
        }

        return recommendations;
    }

    private List<TraceSpan> sortCallChain(List<TraceSpan> chain) {
        // Build parent-child map
        Map<String, List<TraceSpan>> childrenMap = new HashMap<>();
        TraceSpan root = null;

        for (TraceSpan span : chain) {
            if (span.getParentSpanId() == null || span.getParentSpanId().isEmpty()) {
                root = span;
            } else {
                childrenMap.computeIfAbsent(span.getParentSpanId(), k -> new ArrayList<>())
                        .add(span);
            }
        }

        // Build sorted list using DFS
        List<TraceSpan> sorted = new ArrayList<>();
        if (root != null) {
            addSpanAndChildren(root, childrenMap, sorted, 0);
        } else {
            // No clear root, just sort by timestamp
            sorted.addAll(chain);
            sorted.sort(Comparator.comparing(TraceSpan::getTimestamp));
        }

        return sorted;
    }

    private void addSpanAndChildren(TraceSpan span, Map<String, List<TraceSpan>> childrenMap,
                                    List<TraceSpan> sorted, int depth) {
        sorted.add(span);

        List<TraceSpan> children = childrenMap.get(span.getSpanId());
        if (children != null) {
            // Sort children by start time
            children.sort(Comparator.comparing(TraceSpan::getTimestamp));
            for (TraceSpan child : children) {
                addSpanAndChildren(child, childrenMap, sorted, depth + 1);
            }
        }
    }

    // Additional analysis methods

    public Map<String, Double> calculateTimeBreakdown(List<TraceSpan> chain) {
        Map<String, Double> breakdown = new HashMap<>();

        for (TraceSpan span : chain) {
            String operation = normalizeOperationName(span);
            breakdown.merge(operation, span.getDurationMs(), Double::sum);
        }

        return breakdown;
    }

    public List<String> findCriticalPath(List<TraceSpan> chain) {
        // Sort chain to ensure parent-child order
        List<TraceSpan> sorted = sortCallChain(chain);
        List<String> criticalPath = new ArrayList<>();

        // The critical path is the longest sequence from root to leaf
        // For now, just return the operation names in order
        for (TraceSpan span : sorted) {
            criticalPath.add(span.getOperationName() + " (" +
                    String.format("%.2fms", span.getDurationMs()) + ")");
        }

        return criticalPath;
    }

    private String normalizeOperationName(String operation) {
        if (operation == null) return operation;

        // Remove CGLIB proxy suffixes
        if (operation.contains("$$EnhancerBySpringCGLIB$$")) {
            operation = operation.substring(0, operation.indexOf("$$EnhancerBySpringCGLIB$$"));
        }

        // Remove CGLIB method prefixes
        if (operation.contains("CGLIB$")) {
            int cglibIndex = operation.indexOf("CGLIB$");
            if (cglibIndex > 0) {
                operation = operation.substring(0, cglibIndex - 1);
            }
        }

        return operation;
    }

    private String determineSeverity(double avgDuration, double maxDuration, double errorRate) {
        if (errorRate > 0.1 || avgDuration > 5000 || maxDuration > 10000) {
            return "HIGH";
        } else if (errorRate > 0.05 || avgDuration > 2000 || maxDuration > 5000) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private int getSeverityWeight(String severity) {
        return switch (severity) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private List<String> findRelatedOperations(String operation, List<TraceSpan> allTraces) {
        Set<String> traceIds = allTraces.stream()
                .filter(t -> normalizeOperationName(t).equals(operation))
                .map(TraceSpan::getTraceId)
                .collect(Collectors.toSet());

        return allTraces.stream()
                .filter(t -> traceIds.contains(t.getTraceId()) &&
                        !normalizeOperationName(t).equals(operation))
                .map(t -> normalizeOperationName(t))
                .distinct()
                .limit(5)
                .collect(Collectors.toList());
    }

    private String analyzeOperationType(String operationName) {
        String op = operationName.toLowerCase();

        // Database/JPA operations
        if (op.contains("repository") || op.contains("dao") || op.contains("jpa") ||
                op.contains("findall") || op.contains("findby") || op.contains("save") ||
                op.contains("delete") || op.contains("update") || op.contains("query")) {
            return "DATABASE";
        }

        // HTTP/REST operations
        if (op.contains("http") || op.contains("rest") || op.contains("client") ||
                op.contains("exchange") || op.contains("getfor") || op.contains("postfor") ||
                op.contains("webclient") || op.contains("feign")) {
            return "HTTP";
        }

        // Controller operations
        if (op.contains("controller")) {
            return "CONTROLLER";
        }

        // Service layer
        if (op.contains("service")) {
            return "SERVICE";
        }

        // File operations
        if (op.contains("file") || op.contains("upload") || op.contains("download") ||
                op.contains("read") || op.contains("write")) {
            return "FILE_IO";
        }

        return "OTHER";
    }
}
