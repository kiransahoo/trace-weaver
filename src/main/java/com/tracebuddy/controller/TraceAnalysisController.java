package com.tracebuddy.controller;

import com.tracebuddy.model.*;
import com.tracebuddy.factory.TraceMonitorEngineFactory;
import com.tracebuddy.engine.TraceMonitorEngine;
import com.tracebuddy.service.hotspot.HotspotDetectionService;
import com.tracebuddy.service.llm.LLMAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Modular Controller that delegates to appropriate engine
 * Preserves all original endpoints and logic
 * @author kiransahoo
 */
@Slf4j
@RestController
@RequestMapping("/api/traces")
@RequiredArgsConstructor
public class TraceAnalysisController {

    private final TraceMonitorEngineFactory engineFactory;
    private final HotspotDetectionService hotspotDetectionService;
    private final LLMAnalysisService llmAnalysisService;

    @Autowired
    private ApplicationContext applicationContext;

    private TraceMonitorEngine getEngine() {
        return engineFactory.getEngine();
    }

    @PostMapping("/analyze")
    public ResponseEntity<AnalysisResponse> analyzeTraces(@RequestBody QueryRequest request) {
        List<TraceSpan> traces;

        // Handle natural language queries for class/package/resource filtering
        String className = request.getClassName();
        String packageName = request.getPackageName();
        String resourceGroup = request.getResourceGroup();
        String instrumentationKey = request.getInstrumentationKey();
        String cloudRoleName = request.getCloudRoleName();

        log.info("=== ANALYZE REQUEST using {} ===", getEngine().getEngineType());
        log.info("Query: {}", request.getQuery());
        log.info("Resource Group: {}", request.getResourceGroup());
        log.info("Instrumentation Key: {}", request.getInstrumentationKey());
        log.info("Cloud Role Name: {}", request.getCloudRoleName());
        log.info("Class Name: {}", request.getClassName());
        log.info("Package Name: {}", request.getPackageName());
        log.info("====================");

        // If no explicit filters but query mentions them, try to extract
        if ((className == null && packageName == null && resourceGroup == null &&
                instrumentationKey == null && cloudRoleName == null) && request.getQuery() != null) {
            ExtractedFilter filter = extractClassPackageFromQuery(request.getQuery());
            if (filter != null) {
                className = filter.className;
                packageName = filter.packageName;
                resourceGroup = filter.resourceGroup;
                instrumentationKey = filter.instrumentationKey;
                cloudRoleName = filter.cloudRoleName;

                if (filter.timeRange != null) {
                    request.setTimeRange(filter.timeRange);
                    log.info("Using time range from natural language query: {}", filter.timeRange);
                }
            }
        }

        // Determine what type of analysis is being requested
        boolean isErrorQuery = request.getQuery() != null &&
                request.getQuery().toLowerCase().matches(".*\\b(error|exception|fail|issue).*");

        // Get traces based on query type - using single method with all filters
        if (isErrorQuery) {
            traces = getEngine().queryErrorTracesWithAllFilters(
                    request.getTimeRange() != null ? request.getTimeRange() : "1h",
                    resourceGroup,
                    instrumentationKey,
                    cloudRoleName,
                    className,
                    packageName,
                    request.isIncludeSubPackages()
            );
        } else {
            traces = getEngine().queryTracesWithAllFilters(
                    request.getDurationThresholdMs() != null ? request.getDurationThresholdMs() : 1000,
                    request.getTimeRange() != null ? request.getTimeRange() : "1h",
                    resourceGroup,
                    instrumentationKey,
                    cloudRoleName,
                    className,
                    packageName,
                    request.isIncludeSubPackages()
            );
        }

        // Calculate statistics from the FILTERED traces
        Map<String, Object> statistics = getEngine().calculateStatisticsFromTraces(traces);

        // Add logging for statistics
        log.info("=== STATISTICS CALCULATION ===");
        log.info("Total traces after ALL filters: {}", traces.size());
        log.info("Calculated P95: {}ms", statistics.get("percentile95"));
        log.info("Calculated Avg: {}ms", statistics.get("avgDuration"));
        log.info("============================");

        // Create comprehensive filter context
        Map<String, Object> filterContext = new HashMap<>();
        filterContext.put("calculatedFrom", "filtered_traces");
        filterContext.put("traceCount", traces.size());

        // Create a filters map for detailed filter information
        Map<String, Object> filters = new HashMap<>();
        filters.put("timeRange", request.getTimeRange() != null ? request.getTimeRange() : "1h");
        filters.put("durationThreshold", request.getDurationThresholdMs() != null ? request.getDurationThresholdMs() : 1000);
        if (className != null) filters.put("className", className);
        if (packageName != null) filters.put("packageName", packageName);
        if (resourceGroup != null) filters.put("resourceGroup", resourceGroup);
        if (instrumentationKey != null) filters.put("instrumentationKey", instrumentationKey);
        if (cloudRoleName != null) filters.put("cloudRoleName", cloudRoleName);

        filterContext.put("filters", filters);
        filterContext.put("totalFilteredTraces", traces.size());
        statistics.put("filterContext", filterContext);
        statistics.put("context", filterContext); // Add both for backward compatibility

        List<PerformanceHotspot> hotspots = hotspotDetectionService.detectHotspots(traces);

        // Build enhanced query for LLM with all context
        String enhancedQuery = request.getQuery();
        if ((className != null || packageName != null || resourceGroup != null ||
                instrumentationKey != null || cloudRoleName != null) && enhancedQuery != null) {
            enhancedQuery = buildComprehensiveContextualQuery(request.getQuery(), className, packageName,
                    resourceGroup, instrumentationKey, cloudRoleName);
        }

        String llmAnalysis = llmAnalysisService.analyzeTraces(traces, hotspots, statistics);

        String queryResponse = null;
        if (enhancedQuery != null && !enhancedQuery.isEmpty()) {
            queryResponse = llmAnalysisService.answerQuery(enhancedQuery, traces, hotspots);
        }

        AnalysisResponse response = AnalysisResponse.builder()
                .traces(traces)
                .hotspots(hotspots)
                .statistics(statistics)
                .llmAnalysis(queryResponse != null ? queryResponse : llmAnalysis)
                .suggestions(generateSuggestions(hotspots))
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/errors")
    public ResponseEntity<List<TraceSpan>> getErrorTraces(
            @RequestParam(defaultValue = "1h") String timeRange) {
        List<TraceSpan> errorTraces = getEngine().queryErrorTraces(timeRange);
        return ResponseEntity.ok(errorTraces);
    }

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(
            @RequestParam(defaultValue = "1h") String timeRange) {
        Map<String, Object> stats = getEngine().getTraceStatistics(timeRange);
        return ResponseEntity.ok(stats);
    }

    @PostMapping("/analyze/hotspot/{operation}")
    public ResponseEntity<Map<String, Object>> analyzeSpecificHotspot(
            @PathVariable String operation,
            @RequestBody Map<String, Object> request) {

        try {
            // Skip querying traces - just analyze the method directly
            String analysis = llmAnalysisService.analyzeMethodPerformance(
                    operation,
                    null,  // No need for slowest trace
                    null   // No need for all traces
            );

            Map<String, Object> response = new HashMap<>();
            response.put("operation", operation);
            response.put("analysis", analysis);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error analyzing hotspot", e);
            return ResponseEntity.status(500).body(
                    Map.of("error", "Failed to analyze hotspot: " + e.getMessage())
            );
        }
    }

    @GetMapping("/errors/methods")
    public ResponseEntity<Map<String, ErrorMethodInfo>> getMethodsWithErrors(
            @RequestParam(defaultValue = "1h") String timeRange) {
        Map<String, ErrorMethodInfo> errorMethods = getEngine().analyzeErrorMethods(timeRange);
        return ResponseEntity.ok(errorMethods);
    }

    @GetMapping("/errors/traces")
    public ResponseEntity<List<TraceSpan>> getErrorTraceDetails(
            @RequestParam(defaultValue = "1h") String timeRange) {
        List<TraceSpan> errorTraces = getEngine().queryMethodsWithErrors(timeRange);
        return ResponseEntity.ok(errorTraces);
    }

    @PostMapping("/errors/analyze")
    public ResponseEntity<ErrorAnalysisResponse> analyzeErrors(@RequestBody QueryRequest request) {
        String timeRange = request.getTimeRange() != null ? request.getTimeRange() : "1h";

        // Get error methods with optional class/package filtering
        Map<String, ErrorMethodInfo> errorMethods = getEngine().analyzeErrorMethodsByClass(
                timeRange,
                request.getClassName(),
                request.getPackageName(),
                request.isIncludeSubPackages()
        );

        // Get error traces with optional class/package filtering
        List<TraceSpan> errorTraces = getEngine().queryMethodsWithErrorsByClass(
                timeRange,
                request.getClassName(),
                request.getPackageName(),
                request.isIncludeSubPackages()
        );

        // Get overall statistics
        Map<String, Object> statistics = getEngine().getTraceStatistics(timeRange);

        // Add filtering context to statistics
        if (request.getClassName() != null || request.getPackageName() != null) {
            Map<String, Object> filterInfo = new HashMap<>();
            filterInfo.put("className", request.getClassName());
            filterInfo.put("packageName", request.getPackageName());
            filterInfo.put("includeSubPackages", request.isIncludeSubPackages());
            filterInfo.put("filteredErrorCount", errorTraces.size());
            filterInfo.put("filteredMethodCount", errorMethods.size());
            statistics.put("filterInfo", filterInfo);
        }

        // Use LLM to analyze error patterns with class/package context
        String errorAnalysis = "";
        if (request.getQuery() != null && !request.getQuery().isEmpty()) {
            // Build enhanced query with class/package context
            String enhancedQuery = buildEnhancedErrorQuery(request);
            errorAnalysis = llmAnalysisService.analyzeErrors(enhancedQuery, errorMethods, errorTraces);
        }

        ErrorAnalysisResponse response = ErrorAnalysisResponse.builder()
                .errorMethods(errorMethods)
                .errorTraces(errorTraces)
                .statistics(statistics)
                .analysis(errorAnalysis)
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/performance/slow-methods")
    public ResponseEntity<Map<String, Object>> getSlowMethods(
            @RequestParam(defaultValue = "1h") String timeRange,
            @RequestParam(defaultValue = "5000") Long slowThresholdMs) {

        Map<String, Object> response = new HashMap<>();

        try {
            // Get all slow operations
            List<TraceSpan> slowTraces = getEngine().querySlowOperations(timeRange, slowThresholdMs);

            // Group by operation name to find patterns
            Map<String, List<TraceSpan>> slowMethodGroups = slowTraces.stream()
                    .collect(Collectors.groupingBy(t -> normalizeOperationName(t.getOperationName())));

            // Calculate statistics for each slow method
            List<Map<String, Object>> slowMethodStats = new ArrayList<>();

            for (Map.Entry<String, List<TraceSpan>> entry : slowMethodGroups.entrySet()) {
                String methodName = entry.getKey();
                List<TraceSpan> methodTraces = entry.getValue();

                Map<String, Object> methodStat = new HashMap<>();
                methodStat.put("methodName", methodName);
                methodStat.put("occurrenceCount", methodTraces.size());
                methodStat.put("avgDuration", methodTraces.stream()
                        .mapToDouble(TraceSpan::getDurationMs)
                        .average()
                        .orElse(0));
                methodStat.put("maxDuration", methodTraces.stream()
                        .mapToDouble(TraceSpan::getDurationMs)
                        .max()
                        .orElse(0));
                methodStat.put("minDuration", methodTraces.stream()
                        .mapToDouble(TraceSpan::getDurationMs)
                        .min()
                        .orElse(0));
                methodStat.put("p95Duration", calculatePercentile(methodTraces, 95));
                methodStat.put("p99Duration", calculatePercentile(methodTraces, 99));

                // Add sample slow trace for investigation
                methodTraces.stream()
                        .max(Comparator.comparing(TraceSpan::getDurationMs))
                        .ifPresent(slowest -> {
                            methodStat.put("slowestTrace", Map.of(
                                    "traceId", slowest.getTraceId(),
                                    "duration", slowest.getDurationMs(),
                                    "timestamp", slowest.getTimestamp(),
                                    "attributes", slowest.getAttributes()
                            ));
                        });

                slowMethodStats.add(methodStat);
            }

            // Sort by average duration descending
            slowMethodStats.sort((a, b) ->
                    Double.compare((Double) b.get("avgDuration"), (Double) a.get("avgDuration")));

            response.put("slowMethods", slowMethodStats);
            response.put("totalSlowOperations", slowTraces.size());
            response.put("thresholdMs", slowThresholdMs);
            response.put("timeRange", timeRange);

            // Add performance insights
            response.put("insights", generatePerformanceInsights(slowMethodStats, slowThresholdMs));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error analyzing slow methods", e);
            response.put("error", "Failed to analyze slow methods: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/performance/bottlenecks")
    public ResponseEntity<Map<String, Object>> getPerformanceBottlenecks(
            @RequestParam(defaultValue = "1h") String timeRange) {

        Map<String, Object> response = new HashMap<>();

        try {
            // Get all traces to analyze patterns
            List<TraceSpan> allTraces = getEngine().queryTraces(0, timeRange);

            // Detect various types of bottlenecks
            Map<String, Object> bottlenecks = new HashMap<>();

            // 1. Find operations with high variance (unstable performance)
            bottlenecks.put("highVariance", findHighVarianceOperations(allTraces));

            // 2. Find operations that are getting slower over time
            bottlenecks.put("degradingPerformance", findDegradingPerformance(allTraces));

            // 3. Find operations with sudden spikes
            bottlenecks.put("performanceSpikes", findPerformanceSpikes(allTraces));

            // 4. Find most time-consuming operation chains
            bottlenecks.put("expensiveCallChains", findExpensiveCallChains(allTraces));

            // 5. Find operations consuming most total time
            bottlenecks.put("totalTimeConsumers", findTotalTimeConsumers(allTraces));

            response.put("bottlenecks", bottlenecks);
            response.put("analyzedTraces", allTraces.size());
            response.put("timeRange", timeRange);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error analyzing performance bottlenecks", e);
            response.put("error", "Failed to analyze bottlenecks: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/performance/analyze")
    public ResponseEntity<Map<String, Object>> analyzePerformance(@RequestBody QueryRequest request) {
        Map<String, Object> response = new HashMap<>();
        String timeRange = request.getTimeRange() != null ? request.getTimeRange() : "1h";

        try {
            // Get all traces
            List<TraceSpan> traces = getEngine().queryTraces(0, timeRange);

            // Since all operations are marked successful, focus on performance
            Map<String, Object> performanceAnalysis = new HashMap<>();

            // 1. Overall performance metrics
            performanceAnalysis.put("overallMetrics", calculateOverallMetrics(traces));

            // 2. Performance distribution
            performanceAnalysis.put("distribution", calculatePerformanceDistribution(traces));

            // 3. Slowest operations
            performanceAnalysis.put("slowestOperations", findSlowestOperations(traces, 10));

            // 4. Performance trends
            performanceAnalysis.put("trends", analyzePerformanceTrends(traces));

            // 5. Recommendations
            performanceAnalysis.put("recommendations", generatePerformanceRecommendations(traces));

            // If user asked a specific query, use LLM to analyze
            if (request.getQuery() != null && !request.getQuery().isEmpty()) {
                String llmInsights = llmAnalysisService.analyzePerformance(
                        request.getQuery(),
                        traces,
                        performanceAnalysis
                );
                performanceAnalysis.put("aiInsights", llmInsights);
            }

            response.put("performanceAnalysis", performanceAnalysis);
            response.put("traceCount", traces.size());
            response.put("timeRange", timeRange);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error in performance analysis", e);
            response.put("error", "Failed to analyze performance: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/errors/methods/filtered")
    public ResponseEntity<Map<String, ErrorMethodInfo>> getErrorMethodsFiltered(
            @RequestBody QueryRequest request) {
        String timeRange = request.getTimeRange() != null ? request.getTimeRange() : "1h";

        Map<String, ErrorMethodInfo> errorMethods = getEngine().analyzeErrorMethodsByClass(
                timeRange,
                request.getClassName(),
                request.getPackageName(),
                request.isIncludeSubPackages()
        );

        return ResponseEntity.ok(errorMethods);
    }

    @PostMapping("/errors/traces/filtered")
    public ResponseEntity<List<TraceSpan>> getErrorTracesFiltered(
            @RequestBody QueryRequest request) {
        String timeRange = request.getTimeRange() != null ? request.getTimeRange() : "1h";

        List<TraceSpan> errorTraces = getEngine().queryMethodsWithErrorsByClass(
                timeRange,
                request.getClassName(),
                request.getPackageName(),
                request.isIncludeSubPackages()
        );

        return ResponseEntity.ok(errorTraces);
    }

    @GetMapping("/errors/classes")
    public ResponseEntity<Set<String>> getErrorClasses(
            @RequestParam(defaultValue = "1h") String timeRange) {
        Set<String> errorClasses = getEngine().getErrorClassNames(timeRange);
        return ResponseEntity.ok(errorClasses);
    }

    @GetMapping("/errors/packages")
    public ResponseEntity<Set<String>> getErrorPackages(
            @RequestParam(defaultValue = "1h") String timeRange) {
        Set<String> errorPackages = getEngine().getErrorPackageNames(timeRange);
        return ResponseEntity.ok(errorPackages);
    }

    @GetMapping("/errors/statistics/by-class")
    public ResponseEntity<Map<String, ErrorClassStatistics>> getErrorStatisticsByClass(
            @RequestParam(defaultValue = "1h") String timeRange) {
        Map<String, ErrorClassStatistics> statistics =
                getEngine().getErrorStatisticsByClass(timeRange);
        return ResponseEntity.ok(statistics);
    }

    @GetMapping("/classes")
    public ResponseEntity<Set<String>> getAllClasses(
            @RequestParam(defaultValue = "1h") String timeRange) {
        Set<String> classes = getEngine().getAllClassNames(timeRange);
        return ResponseEntity.ok(classes);
    }

    @GetMapping("/packages")
    public ResponseEntity<Set<String>> getAllPackages(
            @RequestParam(defaultValue = "1h") String timeRange) {
        Set<String> packages = getEngine().getAllPackageNames(timeRange);
        return ResponseEntity.ok(packages);
    }

    @GetMapping("/hotspots/class/{className}")
    public ResponseEntity<List<PerformanceHotspot>> getHotspotsForClass(
            @PathVariable String className,
            @RequestParam(defaultValue = "1h") String timeRange) {

        List<TraceSpan> classTraces = getEngine().queryTracesWithAllFilters(
                0L, timeRange, null, null, null, className, null, false
        );
        List<PerformanceHotspot> hotspots = hotspotDetectionService.detectHotspots(classTraces);

        return ResponseEntity.ok(hotspots);
    }

    @PostMapping("/hotspots/package")
    public ResponseEntity<List<PerformanceHotspot>> getHotspotsForPackage(
            @RequestBody Map<String, Object> request) {

        String packageName = (String) request.get("packageName");
        String timeRange = (String) request.getOrDefault("timeRange", "1h");
        boolean includeSubPackages = (boolean) request.getOrDefault("includeSubPackages", true);

        List<TraceSpan> packageTraces = getEngine().queryTracesByClass(
                0L, timeRange, null, packageName, includeSubPackages);
        List<PerformanceHotspot> hotspots = hotspotDetectionService.detectHotspots(packageTraces);

        return ResponseEntity.ok(hotspots);
    }

    // ============================================
    // Private Helper Methods
    // ============================================

    private List<String> generateSuggestions(List<PerformanceHotspot> hotspots) {
        List<String> suggestions = new ArrayList<>();

        if (hotspots.stream().anyMatch(h -> h.getSeverity().equals("HIGH"))) {
            suggestions.add("Critical performance issues detected - immediate action recommended");
        }

        if (hotspots.stream().anyMatch(h -> h.getErrorRate() > 0.1)) {
            suggestions.add("High error rates detected - review error handling and retry logic");
        }

        if (hotspots.stream().anyMatch(h -> h.getAvgDurationMs() > 5000)) {
            suggestions.add("Extremely slow operations detected - consider async processing");
        }

        return suggestions;
    }

    private String buildComprehensiveContextualQuery(String originalQuery, String className, String packageName,
                                                     String resourceGroup, String instrumentationKey, String cloudRoleName) {
        StringBuilder contextualQuery = new StringBuilder(originalQuery);
        List<String> contexts = new ArrayList<>();

        if (className != null) {
            contexts.add("class: " + className);
        }
        if (packageName != null) {
            contexts.add("package: " + packageName);
        }
        if (resourceGroup != null) {
            contexts.add("resource group: " + resourceGroup);
        }
        if (instrumentationKey != null) {
            contexts.add("instrumentation key: " + instrumentationKey);
        }
        if (cloudRoleName != null) {
            contexts.add("cloud role: " + cloudRoleName);
        }

        if (!contexts.isEmpty()) {
            contextualQuery.append(" (analyzing ").append(String.join(", ", contexts)).append(")");
        }

        return contextualQuery.toString();
    }

    private String buildEnhancedErrorQuery(QueryRequest request) {
        StringBuilder query = new StringBuilder(request.getQuery());

        if (request.getClassName() != null) {
            query.append(" (filtered by class: ").append(request.getClassName()).append(")");
        } else if (request.getPackageName() != null) {
            query.append(" (filtered by package: ").append(request.getPackageName());
            if (!request.isIncludeSubPackages()) {
                query.append(" - excluding sub-packages");
            }
            query.append(")");
        }

        return query.toString();
    }

    private String normalizeOperationName(String operation) {
        if (operation == null) return "Unknown";

        // Remove CGLIB proxy suffixes
        if (operation.contains("$$EnhancerBySpringCGLIB$$")) {
            operation = operation.substring(0, operation.indexOf("$$EnhancerBySpringCGLIB$$"));
        }

        // Remove CGLIB method prefixes
        if (operation.contains(".CGLIB$")) {
            int idx = operation.lastIndexOf('.');
            if (idx > 0) {
                operation = operation.substring(0, idx);
            }
        }

        return operation;
    }

    private double calculatePercentile(List<TraceSpan> traces, int percentile) {
        if (traces.isEmpty()) return 0;

        List<Double> durations = traces.stream()
                .map(TraceSpan::getDurationMs)
                .sorted()
                .collect(Collectors.toList());

        int n = durations.size();
        if (n == 1) return durations.get(0);

        // Standard percentile calculation with interpolation
        double position = (percentile / 100.0) * (n - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);

        if (lower == upper) {
            return durations.get(lower);
        }

        // Linear interpolation between lower and upper
        double lowerValue = durations.get(lower);
        double upperValue = durations.get(upper);
        double fraction = position - lower;

        return lowerValue + fraction * (upperValue - lowerValue);
    }

    private List<String> generatePerformanceInsights(List<Map<String, Object>> slowMethods, long threshold) {
        List<String> insights = new ArrayList<>();

        if (slowMethods.isEmpty()) {
            insights.add("No operations exceeding " + threshold + "ms threshold found.");
            return insights;
        }

        // Find critical operations
        long criticalCount = slowMethods.stream()
                .filter(m -> (Double) m.get("maxDuration") > threshold * 2)
                .count();

        if (criticalCount > 0) {
            insights.add("CRITICAL: " + criticalCount + " operations have instances taking more than " +
                    (threshold * 2) + "ms");
        }

        // Find most frequent slow operations
        slowMethods.stream()
                .filter(m -> (Integer) m.get("occurrenceCount") > 10)
                .findFirst()
                .ifPresent(m -> insights.add("'" + m.get("methodName") +
                        "' is consistently slow with " + m.get("occurrenceCount") + " occurrences"));

        // Calculate total impact
        double totalSlowTime = slowMethods.stream()
                .mapToDouble(m -> (Double) m.get("avgDuration") * (Integer) m.get("occurrenceCount"))
                .sum();

        insights.add(String.format("Total time spent in slow operations: %.2f seconds", totalSlowTime / 1000));

        return insights;
    }

    private List<Map<String, Object>> findHighVarianceOperations(List<TraceSpan> traces) {
        Map<String, List<TraceSpan>> grouped = traces.stream()
                .collect(Collectors.groupingBy(t -> normalizeOperationName(t.getOperationName())));

        List<Map<String, Object>> highVarianceOps = new ArrayList<>();

        for (Map.Entry<String, List<TraceSpan>> entry : grouped.entrySet()) {
            if (entry.getValue().size() < 5) continue; // Need enough samples

            DoubleSummaryStatistics stats = entry.getValue().stream()
                    .mapToDouble(TraceSpan::getDurationMs)
                    .summaryStatistics();

            double mean = stats.getAverage();
            double variance = entry.getValue().stream()
                    .mapToDouble(t -> Math.pow(t.getDurationMs() - mean, 2))
                    .average()
                    .orElse(0);

            double stdDev = Math.sqrt(variance);
            double coefficientOfVariation = stdDev / mean;

            if (coefficientOfVariation > 0.5) { // High variance threshold
                Map<String, Object> opInfo = new HashMap<>();
                opInfo.put("operation", entry.getKey());
                opInfo.put("avgDuration", mean);
                opInfo.put("stdDeviation", stdDev);
                opInfo.put("coefficientOfVariation", coefficientOfVariation);
                opInfo.put("minDuration", stats.getMin());
                opInfo.put("maxDuration", stats.getMax());
                opInfo.put("sampleCount", entry.getValue().size());

                highVarianceOps.add(opInfo);
            }
        }

        highVarianceOps.sort((a, b) ->
                Double.compare((Double) b.get("coefficientOfVariation"),
                        (Double) a.get("coefficientOfVariation")));

        return highVarianceOps.stream().limit(10).collect(Collectors.toList());
    }

    private List<Map<String, Object>> findDegradingPerformance(List<TraceSpan> traces) {
        Map<String, List<TraceSpan>> grouped = traces.stream()
                .collect(Collectors.groupingBy(t -> normalizeOperationName(t.getOperationName())));

        List<Map<String, Object>> degradingOps = new ArrayList<>();

        for (Map.Entry<String, List<TraceSpan>> entry : grouped.entrySet()) {
            if (entry.getValue().size() < 10) continue;

            // Sort by timestamp
            List<TraceSpan> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparing(TraceSpan::getTimestamp))
                    .collect(Collectors.toList());

            // Calculate trend using simple linear regression
            int n = sorted.size();
            double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;

            for (int i = 0; i < n; i++) {
                double x = i;
                double y = sorted.get(i).getDurationMs();
                sumX += x;
                sumY += y;
                sumXY += x * y;
                sumX2 += x * x;
            }

            double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);

            if (slope > 0) { // Performance is degrading
                Map<String, Object> opInfo = new HashMap<>();
                opInfo.put("operation", entry.getKey());
                opInfo.put("degradationRate", slope);
                opInfo.put("startAvg", sorted.subList(0, Math.min(5, n)).stream()
                        .mapToDouble(TraceSpan::getDurationMs).average().orElse(0));
                opInfo.put("endAvg", sorted.subList(Math.max(0, n - 5), n).stream()
                        .mapToDouble(TraceSpan::getDurationMs).average().orElse(0));
                opInfo.put("sampleCount", n);

                degradingOps.add(opInfo);
            }
        }

        degradingOps.sort((a, b) ->
                Double.compare((Double) b.get("degradationRate"),
                        (Double) a.get("degradationRate")));

        return degradingOps.stream().limit(10).collect(Collectors.toList());
    }

    private List<Map<String, Object>> findPerformanceSpikes(List<TraceSpan> traces) {
        Map<String, List<TraceSpan>> grouped = traces.stream()
                .collect(Collectors.groupingBy(t -> normalizeOperationName(t.getOperationName())));

        List<Map<String, Object>> spikeOps = new ArrayList<>();

        for (Map.Entry<String, List<TraceSpan>> entry : grouped.entrySet()) {
            if (entry.getValue().size() < 5) continue;

            double avg = entry.getValue().stream()
                    .mapToDouble(TraceSpan::getDurationMs)
                    .average()
                    .orElse(0);

            List<TraceSpan> spikes = entry.getValue().stream()
                    .filter(t -> t.getDurationMs() > avg * 3) // Spike threshold: 3x average
                    .collect(Collectors.toList());

            if (!spikes.isEmpty()) {
                Map<String, Object> opInfo = new HashMap<>();
                opInfo.put("operation", entry.getKey());
                opInfo.put("avgDuration", avg);
                opInfo.put("spikeCount", spikes.size());
                opInfo.put("maxSpike", spikes.stream()
                        .mapToDouble(TraceSpan::getDurationMs)
                        .max()
                        .orElse(0));
                opInfo.put("spikeRatio", opInfo.get("maxSpike") + " / " + avg);

                spikeOps.add(opInfo);
            }
        }

        spikeOps.sort((a, b) ->
                Integer.compare((Integer) b.get("spikeCount"),
                        (Integer) a.get("spikeCount")));

        return spikeOps.stream().limit(10).collect(Collectors.toList());
    }

    private List<Map<String, Object>> findExpensiveCallChains(List<TraceSpan> traces) {
        // Group by trace ID to find complete call chains
        Map<String, List<TraceSpan>> chainMap = traces.stream()
                .filter(t -> t.getTraceId() != null && !t.getTraceId().isEmpty())
                .collect(Collectors.groupingBy(TraceSpan::getTraceId));

        List<Map<String, Object>> expensiveChains = new ArrayList<>();

        for (Map.Entry<String, List<TraceSpan>> entry : chainMap.entrySet()) {
            if (entry.getValue().size() < 2) continue; // Need multiple spans for a chain

            double totalDuration = entry.getValue().stream()
                    .mapToDouble(TraceSpan::getDurationMs)
                    .sum();

            Map<String, Object> chainInfo = new HashMap<>();
            chainInfo.put("traceId", entry.getKey());
            chainInfo.put("totalDuration", totalDuration);
            chainInfo.put("spanCount", entry.getValue().size());
            chainInfo.put("operations", entry.getValue().stream()
                    .map(TraceSpan::getOperationName)
                    .collect(Collectors.toList()));

            // Find the root span
            entry.getValue().stream()
                    .filter(s -> s.getParentSpanId() == null || s.getParentSpanId().isEmpty())
                    .findFirst()
                    .ifPresent(root -> chainInfo.put("rootOperation", root.getOperationName()));

            expensiveChains.add(chainInfo);
        }

        expensiveChains.sort((a, b) ->
                Double.compare((Double) b.get("totalDuration"),
                        (Double) a.get("totalDuration")));

        return expensiveChains.stream().limit(10).collect(Collectors.toList());
    }

    private List<Map<String, Object>> findTotalTimeConsumers(List<TraceSpan> traces) {
        Map<String, DoubleSummaryStatistics> operationStats = traces.stream()
                .collect(Collectors.groupingBy(
                        t -> normalizeOperationName(t.getOperationName()),
                        Collectors.summarizingDouble(TraceSpan::getDurationMs)
                ));

        List<Map<String, Object>> timeConsumers = new ArrayList<>();

        for (Map.Entry<String, DoubleSummaryStatistics> entry : operationStats.entrySet()) {
            Map<String, Object> opInfo = new HashMap<>();
            opInfo.put("operation", entry.getKey());
            opInfo.put("totalTime", entry.getValue().getSum());
            opInfo.put("avgDuration", entry.getValue().getAverage());
            opInfo.put("callCount", entry.getValue().getCount());
            opInfo.put("maxDuration", entry.getValue().getMax());

            timeConsumers.add(opInfo);
        }

        timeConsumers.sort((a, b) ->
                Double.compare((Double) b.get("totalTime"),
                        (Double) a.get("totalTime")));

        return timeConsumers.stream().limit(10).collect(Collectors.toList());
    }

    private Map<String, Object> calculateOverallMetrics(List<TraceSpan> traces) {
        DoubleSummaryStatistics stats = traces.stream()
                .mapToDouble(TraceSpan::getDurationMs)
                .summaryStatistics();

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalTraces", traces.size());
        metrics.put("avgDuration", stats.getAverage());
        metrics.put("minDuration", stats.getMin());
        metrics.put("maxDuration", stats.getMax());
        metrics.put("p50Duration", calculatePercentile(traces, 50));
        metrics.put("p75Duration", calculatePercentile(traces, 75));
        metrics.put("p90Duration", calculatePercentile(traces, 90));
        metrics.put("p95Duration", calculatePercentile(traces, 95));
        metrics.put("p99Duration", calculatePercentile(traces, 99));

        return metrics;
    }

    private Map<String, Object> calculatePerformanceDistribution(List<TraceSpan> traces) {
        Map<String, Object> distribution = new HashMap<>();

        long under100ms = traces.stream().filter(t -> t.getDurationMs() < 100).count();
        long under500ms = traces.stream().filter(t -> t.getDurationMs() < 500).count();
        long under1s = traces.stream().filter(t -> t.getDurationMs() < 1000).count();
        long under5s = traces.stream().filter(t -> t.getDurationMs() < 5000).count();
        long over5s = traces.stream().filter(t -> t.getDurationMs() >= 5000).count();

        distribution.put("under100ms", under100ms);
        distribution.put("100to500ms", under500ms - under100ms);
        distribution.put("500msTo1s", under1s - under500ms);
        distribution.put("1sTo5s", under5s - under1s);
        distribution.put("over5s", over5s);

        return distribution;
    }

    private List<Map<String, Object>> findSlowestOperations(List<TraceSpan> traces, int limit) {
        return traces.stream()
                .sorted((a, b) -> Double.compare(b.getDurationMs(), a.getDurationMs()))
                .limit(limit)
                .map(t -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("operation", t.getOperationName());
                    info.put("duration", t.getDurationMs());
                    info.put("timestamp", t.getTimestamp());
                    info.put("traceId", t.getTraceId());
                    return info;
                })
                .collect(Collectors.toList());
    }

    private Map<String, Object> analyzePerformanceTrends(List<TraceSpan> traces) {
        Map<String, Object> trends = new HashMap<>();

        // Group by hour to see hourly trends
        Map<Integer, List<TraceSpan>> hourlyGroups = traces.stream()
                .collect(Collectors.groupingBy(t -> t.getTimestamp().getHour()));

        Map<Integer, Double> hourlyAvg = new HashMap<>();
        for (Map.Entry<Integer, List<TraceSpan>> entry : hourlyGroups.entrySet()) {
            double avg = entry.getValue().stream()
                    .mapToDouble(TraceSpan::getDurationMs)
                    .average()
                    .orElse(0);
            hourlyAvg.put(entry.getKey(), avg);
        }

        trends.put("hourlyAverages", hourlyAvg);

        // Find peak hours
        hourlyAvg.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .ifPresent(peak -> trends.put("peakHour", peak.getKey()));

        return trends;
    }

    private List<String> generatePerformanceRecommendations(List<TraceSpan> traces) {
        List<String> recommendations = new ArrayList<>();

        DoubleSummaryStatistics stats = traces.stream()
                .mapToDouble(TraceSpan::getDurationMs)
                .summaryStatistics();

        if (stats.getMax() > 10000) {
            recommendations.add("CRITICAL: Operations taking over 10 seconds detected - immediate optimization required");
        }

        if (stats.getAverage() > 3000) {
            recommendations.add("Average response time exceeds 3 seconds - consider caching and query optimization");
        }

        double p95 = calculatePercentile(traces, 95);
        if (p95 > stats.getAverage() * 3) {
            recommendations.add("High variance detected (P95 is 3x average) - investigate environmental factors");
        }

        // Check for specific patterns
        Map<String, Long> operationCounts = traces.stream()
                .collect(Collectors.groupingBy(
                        t -> normalizeOperationName(t.getOperationName()),
                        Collectors.counting()
                ));

        operationCounts.entrySet().stream()
                .filter(e -> e.getValue() > traces.size() * 0.3)
                .findFirst()
                .ifPresent(e -> recommendations.add(
                        "'" + e.getKey() + "' accounts for " +
                                (e.getValue() * 100 / traces.size()) +
                                "% of operations - optimize this hot path"));

        return recommendations;
    }

    // ============================================
    // Natural Language Query Extraction
    // ============================================

    private static class ExtractedFilter {
        String className;
        String packageName;
        String resourceGroup;
        String instrumentationKey;
        String cloudRoleName;
        String timeRange;
    }

    private ExtractedFilter extractClassPackageFromQuery(String query) {
        if (query == null) return null;

        ExtractedFilter filter = new ExtractedFilter();
        String lowerQuery = query.toLowerCase();

        // Extract time range first and remove it from query
        Pattern timePattern = Pattern.compile(
                "(?:in\\s+)?(?:the\\s+)?(?:last|past)\\s+(\\d+)\\s*(minutes?|mins?|hours?|hrs?|days?|d)",
                Pattern.CASE_INSENSITIVE
        );
        Matcher timeMatcher = timePattern.matcher(query);
        if (timeMatcher.find()) {
            int value = Integer.parseInt(timeMatcher.group(1));
            String unit = timeMatcher.group(2).toLowerCase();

            if (unit.startsWith("min")) {
                filter.timeRange = value + "m";
            } else if (unit.startsWith("h")) {
                filter.timeRange = value + "h";
            } else if (unit.startsWith("d")) {
                filter.timeRange = value + "d";
            }

            // Remove the time phrase from query to prevent conflicts
            query = query.substring(0, timeMatcher.start()) + " " + query.substring(timeMatcher.end());
            query = query.trim().replaceAll("\\s+", " ");
            log.debug("Extracted time range: {}, cleaned query: '{}'", filter.timeRange, query);
        }

        // Extract resource group
        Pattern rgPattern = Pattern.compile(
                "(?:resource\\s+group|rg|resourcegroup|from\\s+rg)\\s+['\"]?([\\w-]+)['\"]?",
                Pattern.CASE_INSENSITIVE
        );
        Matcher rgMatcher = rgPattern.matcher(query);
        if (rgMatcher.find()) {
            filter.resourceGroup = rgMatcher.group(1);
        }

        // Extract instrumentation key
        Pattern keyPattern = Pattern.compile(
                "(?:instrumentation\\s+key|ikey|app\\s+id|appid)\\s+['\"]?([\\w-]+)['\"]?",
                Pattern.CASE_INSENSITIVE
        );
        Matcher keyMatcher = keyPattern.matcher(query);
        if (keyMatcher.find()) {
            filter.instrumentationKey = keyMatcher.group(1);
        }

        // Extract service/role patterns
        Pattern rolePattern = Pattern.compile(
                "(?:service|role|cloud\\s+role|app\\s+role|application|app)\\s+['\"]?([\\w-]+)['\"]?",
                Pattern.CASE_INSENSITIVE
        );
        Matcher roleMatcher = rolePattern.matcher(query);
        if (roleMatcher.find()) {
            String extracted = roleMatcher.group(1);
            if (!Arrays.asList("group", "key", "id", "instrumentation", "resource", "last", "past").contains(extracted.toLowerCase())) {
                filter.cloudRoleName = extracted;
            }
        }

        // Look for patterns like "hotspots in TraceAnalysisController"
        Pattern classPattern = Pattern.compile(
                "(?:hotspots?|slow|performance|bottlenecks?|errors?|issues?|problems?|methods?)\\s+" +
                        "(?:in|from|for|of)\\s+(?:class\\s+)?(\\w+(?:\\.\\w+)*)",
                Pattern.CASE_INSENSITIVE
        );
        Matcher classMatcher = classPattern.matcher(query);
        if (classMatcher.find()) {
            String extracted = classMatcher.group(1);
            if (!extracted.equals(filter.cloudRoleName) && !extracted.equals(filter.resourceGroup)) {
                if (extracted.contains(".") && extracted.toLowerCase().equals(extracted)) {
                    filter.packageName = extracted;
                } else if (!Arrays.asList("service", "role", "app", "application", "last", "past").contains(extracted.toLowerCase())) {
                    filter.className = extracted;
                }
            }
        }

        // Additional pattern matching for other query structures
        if (filter.className == null && filter.packageName == null) {
            Pattern analyzePattern = Pattern.compile(
                    "(?:analyze|check|show|find|get)\\s+(\\w+(?:\\.\\w+)*)\\s+" +
                            "(?:performance|hotspots?|bottlenecks?|slow methods?)",
                    Pattern.CASE_INSENSITIVE
            );
            Matcher analyzeMatcher = analyzePattern.matcher(query);
            if (analyzeMatcher.find()) {
                String extracted = analyzeMatcher.group(1);
                if (!extracted.equals(filter.cloudRoleName)) {
                    if (extracted.contains(".") && extracted.toLowerCase().equals(extracted)) {
                        filter.packageName = extracted;
                    } else {
                        filter.className = extracted;
                    }
                }
            }
        }

        // Look for package patterns
        if (filter.packageName == null) {
            Pattern packagePattern = Pattern.compile("(?:package|pkg)\\s+(\\w+(?:\\.\\w+)+)", Pattern.CASE_INSENSITIVE);
            Matcher packageMatcher = packagePattern.matcher(query);
            if (packageMatcher.find()) {
                filter.packageName = packageMatcher.group(1);
            }
        }

        // Look for explicit class names (CamelCase words)
        if (filter.className == null && filter.cloudRoleName == null) {
            Pattern camelCasePattern = Pattern.compile("\\b([A-Z][a-z]+(?:[A-Z][a-z]+)+)\\b");
            Matcher camelCaseMatcher = camelCasePattern.matcher(query);
            if (camelCaseMatcher.find()) {
                String camelCase = camelCaseMatcher.group(1);
                if (camelCase.endsWith("Controller") || camelCase.endsWith("Service") ||
                        camelCase.endsWith("Repository") || camelCase.endsWith("Component")) {
                    filter.className = camelCase;
                } else if (filter.cloudRoleName == null) {
                    filter.cloudRoleName = camelCase;
                }
            }
        }

        log.debug("Extracted filters - Class: {}, Package: {}, RG: {}, IKey: {}, Role: {}, TimeRange: {}",
                filter.className, filter.packageName, filter.resourceGroup,
                filter.instrumentationKey, filter.cloudRoleName, filter.timeRange);

        return filter;
    }
}
