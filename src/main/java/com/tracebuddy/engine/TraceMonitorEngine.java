package com.tracebuddy.engine;

import com.tracebuddy.config.GitHubProperties;
import com.tracebuddy.model.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core interface for trace monitoring engines
 * Supports Azure Monitor, Loki, Jaeger, and other OTEL backends
 */
public interface TraceMonitorEngine {

    
    // Core query methods
    List<TraceSpan> queryTraces(long durationThresholdMs, String timeRange);
    
    List<TraceSpan> queryTraces(long durationThresholdMs, String timeRange,
                                String resourceGroup, String instrumentationKey,
                                String cloudRoleName);
    
    List<TraceSpan> queryTracesWithAllFilters(
            long durationThresholdMs, String timeRange,
            String resourceGroup, String instrumentationKey,
            String cloudRoleName, String className,
            String packageName, boolean includeSubPackages);
    
    // Error analysis methods
    List<TraceSpan> queryErrorTraces(String timeRange);
    
    List<TraceSpan> queryErrorTraces(String timeRange, String resourceGroup,
                                     String instrumentationKey, String cloudRoleName);
    
    List<TraceSpan> queryErrorTracesWithAllFilters(
            String timeRange, String resourceGroup,
            String instrumentationKey, String cloudRoleName,
            String className, String packageName,
            boolean includeSubPackages);

    /**
     * Query aggregated metrics for a package for SLA monitoring
     */
    Map<String, Object> queryPackageMetricsForAlerts(
            String cloudRoleName,
            String packageName,
            String timeRange,
            Long durationThresholdMs,
            GitHubProperties.SLAConfig slaConfig);

    /**
     * Query top slow operations for a package
     */
    List<PerformanceHotspot> queryTopSlowOperations(
            String cloudRoleName,
            String packageName,
            String timeRange,
            GitHubProperties.SLAConfig slaConfig,
            int topN);

    /**
     * Get a sample trace for a specific operation for detailed analysis
     */
    TraceSpan getSampleTraceForOperation(
            String operationName,
            String cloudRoleName,
            String timeRange);
    
    List<TraceSpan> queryMethodsWithErrors(String timeRange);
    
    List<TraceSpan> queryMethodsWithErrors(String timeRange, String resourceGroup,
                                           String instrumentationKey, String cloudRoleName);
    
    List<TraceSpan> queryMethodsWithErrorsByClass(String timeRange, String className, 
                                                  String packageName, boolean includeSubPackages);
    
    Map<String, ErrorMethodInfo> analyzeErrorMethods(String timeRange);
    
    Map<String, ErrorMethodInfo> analyzeErrorMethodsByClass(
            String timeRange, String className,
            String packageName, boolean includeSubPackages);
    
    // Statistics methods
    Map<String, Object> getTraceStatistics(String timeRange);
    
    Map<String, Object> getTraceStatistics(String timeRange, String resourceGroup,
                                           String instrumentationKey, String cloudRoleName);
    
    Map<String, Object> calculateStatisticsFromTraces(List<TraceSpan> traces);
    
    // Performance analysis
    List<TraceSpan> querySlowOperations(String timeRange, long slowThresholdMs);
    
    // Class/Package analysis
    List<TraceSpan> queryTracesByClass(Long durationThresholdMs, String timeRange,
                                       String className, String packageName,
                                       boolean includeSubPackages);
    
    List<TraceSpan> queryTracesByClass(Long durationThresholdMs, String timeRange,
                                       String className, String packageName,
                                       boolean includeSubPackages,
                                       String resourceGroup, String instrumentationKey,
                                       String cloudRoleName);
    
    Set<String> getAllClassNames(String timeRange);
    Set<String> getAllPackageNames(String timeRange);
    Set<String> getErrorClassNames(String timeRange);
    Set<String> getErrorPackageNames(String timeRange);
    Map<String, ErrorClassStatistics> getErrorStatisticsByClass(String timeRange);
    
    Map<String, Object> getClassPerformanceStats(String className, String timeRange);
    Map<String, Object> getPackagePerformanceStats(String packageName, String timeRange,
                                                   boolean includeSubPackages);
    
    // Utility methods
    String extractClassName(String operation);
    String extractPackageName(String operation);
    boolean testConnection();
    
    // Engine type identifier
    String getEngineType();
}
