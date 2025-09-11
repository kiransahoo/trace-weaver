package com.tracebuddy.engine.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.*;
import com.azure.monitor.query.LogsQueryClient;
import com.azure.monitor.query.LogsQueryClientBuilder;
import com.azure.monitor.query.models.*;
import com.tracebuddy.config.GitHubProperties;
import com.tracebuddy.engine.TraceMonitorEngine;
import com.tracebuddy.model.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Azure Monitor implementation of TraceMonitorEngine
 * Complete implementation with all original logic preserved
 * @author kiransahoo
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "tracebuddy.engine.type", havingValue = "azure", matchIfMissing = true)
public class AzureMonitorEngine implements TraceMonitorEngine {

    private LogsQueryClient logsQueryClient;

    @Value("${azure.monitor.workspace-id}")
    private String workspaceId;

    @Value("${azure.auth.chain:CLI,SERVICE_PRINCIPAL,MANAGED_IDENTITY,DEFAULT}")
    private String authChain;

    @Value("${azure.auth.tenant-id:}")
    private String tenantId;

    @Value("${azure.managed-identity.client-id:}")
    private String managedIdentityClientId;

    @Value("${azure.auth.enable-logging:true}")
    private boolean enableAuthLogging;

    @Value("${spring.profiles.active:local}")
    private String activeProfile;

    @Value("${azure.service-principal.client-id:}")
    private String spClientId;

    @Value("${azure.service-principal.client-secret:}")
    private String spClientSecret;

    @Value("${azure.service-principal.tenant-id:}")
    private String spTenantId;

    @PostConstruct
    public void init() {
        log.info("Initializing Azure Monitor Engine");
        log.info("Active profile: {}", activeProfile);
        log.info("Auth chain: {}", authChain);

        TokenCredential credential = createCredential();
        this.logsQueryClient = new LogsQueryClientBuilder()
                .credential(credential)
                .buildClient();

        if (testConnection()) {
            log.info("✔ Azure Monitor Engine initialized successfully");
        } else {
            log.warn("⚠ Azure Monitor Engine initialized but connection test failed");
        }
    }

    @Override
    public String getEngineType() {
        return "Azure Monitor";
    }

    private TokenCredential createCredential() {
        String[] authMethods = authChain.split(",");
        List<TokenCredential> credentials = new ArrayList<>();

        for (String method : authMethods) {
            method = method.trim().toUpperCase();

            try {
                switch (method) {
                    case "DEFAULT":
                        log.info("Adding DefaultAzureCredential to chain");
                        credentials.add(createDefaultCredential());
                        break;

                    case "CLI":
                    case "AZURE_CLI":
                        log.info("Adding AzureCliCredential to chain");
                        credentials.add(createAzureCliCredential());
                        break;

                    case "MANAGED_IDENTITY":
                    case "MSI":
                        log.info("Adding ManagedIdentityCredential to chain");
                        credentials.add(createManagedIdentityCredential());
                        break;

                    case "ENVIRONMENT":
                        log.info("Adding EnvironmentCredential to chain");
                        credentials.add(createEnvironmentCredential());
                        break;

                    case "WORKLOAD_IDENTITY":
                        log.info("Adding WorkloadIdentityCredential to chain");
                        credentials.add(createWorkloadIdentityCredential());
                        break;

                    case "SERVICE_PRINCIPAL":
                        if (spClientId != null && !spClientId.isEmpty() &&
                                spClientSecret != null && !spClientSecret.isEmpty() &&
                                spTenantId != null && !spTenantId.isEmpty()) {
                            log.info("Adding ClientSecretCredential to chain");
                            credentials.add(createClientSecretCredential());
                        } else {
                            log.debug("Service principal credentials not configured, skipping");
                        }
                        break;

                    default:
                        log.warn("Unknown authentication method: {}", method);
                }
            } catch (Exception e) {
                log.warn("Failed to create {} credential: {}", method, e.getMessage());
            }
        }

        if (credentials.isEmpty()) {
            throw new RuntimeException("No valid authentication methods configured");
        }

        if (credentials.size() == 1) {
            log.info("Using single credential: {}", authMethods[0]);
            return credentials.get(0);
        } else {
            log.info("Creating chained credential with {} methods", credentials.size());
            ChainedTokenCredentialBuilder chainBuilder = new ChainedTokenCredentialBuilder();

            for (TokenCredential cred : credentials) {
                chainBuilder.addLast(cred);
            }

            return chainBuilder.build();
        }
    }

    private TokenCredential createClientSecretCredential() {
        log.info("Creating ClientSecretCredential with client ID: {}", spClientId);
        return new ClientSecretCredentialBuilder()
                .clientId(spClientId)
                .clientSecret(spClientSecret)
                .tenantId(spTenantId)
                .build();
    }

    private TokenCredential createDefaultCredential() {
        DefaultAzureCredentialBuilder builder = new DefaultAzureCredentialBuilder();

        if (tenantId != null && !tenantId.isEmpty()) {
            log.debug("Setting tenant ID for DefaultAzureCredential: {}", tenantId);
            builder.tenantId(tenantId);
        }

        if (managedIdentityClientId != null && !managedIdentityClientId.isEmpty()) {
            log.debug("Setting managed identity client ID for DefaultAzureCredential: {}", managedIdentityClientId);
            builder.managedIdentityClientId(managedIdentityClientId);
        }

        return builder.build();
    }

    private TokenCredential createAzureCliCredential() {
        return new AzureCliCredentialBuilder().build();
    }

    private TokenCredential createManagedIdentityCredential() {
        ManagedIdentityCredentialBuilder builder = new ManagedIdentityCredentialBuilder();

        if (managedIdentityClientId != null && !managedIdentityClientId.isEmpty()) {
            log.info("Using user-assigned managed identity with client ID: {}", managedIdentityClientId);
            builder.clientId(managedIdentityClientId);
        } else {
            log.info("Using system-assigned managed identity");
        }

        return builder.build();
    }

    private TokenCredential createEnvironmentCredential() {
        log.debug("Creating EnvironmentCredential (reads from env vars)");
        return new EnvironmentCredentialBuilder().build();
    }

    private TokenCredential createWorkloadIdentityCredential() {
        WorkloadIdentityCredentialBuilder builder = new WorkloadIdentityCredentialBuilder();

        if (tenantId != null && !tenantId.isEmpty()) {
            log.debug("Setting tenant ID for WorkloadIdentity: {}", tenantId);
            builder.tenantId(tenantId);
        }

        if (managedIdentityClientId != null && !managedIdentityClientId.isEmpty()) {
            log.debug("Setting client ID for WorkloadIdentity: {}", managedIdentityClientId);
            builder.clientId(managedIdentityClientId);
        }

        return builder.build();
    }

    @Override
    public boolean testConnection() {
        try {
            log.debug("Testing Azure Monitor connection...");
            String testQuery = "AppDependencies | take 1";
            QueryTimeInterval interval = new QueryTimeInterval(
                    OffsetDateTime.now().minusMinutes(5),
                    OffsetDateTime.now()
            );

            LogsQueryResult result = logsQueryClient.queryWorkspace(
                    workspaceId,
                    testQuery,
                    interval
            );

            log.info("Azure Monitor connection test successful");
            return true;
        } catch (Exception e) {
            if (enableAuthLogging) {
                log.error("Azure Monitor connection test failed", e);
            } else {
                log.error("Azure Monitor connection test failed: {}", e.getMessage());
            }
            return false;
        }
    }

    @Override
    public List<TraceSpan> queryTraces(long durationThresholdMs, String timeRange) {
        String query = buildKustoQuery(durationThresholdMs, timeRange);
        QueryTimeInterval interval = parseTimeRange(timeRange);

        log.info("Executing query for traces with duration > {}ms in timeRange: {}", durationThresholdMs, timeRange);

        try {
            LogsQueryResult result = logsQueryClient.queryWorkspace(
                    workspaceId,
                    query,
                    interval
            );

            List<TraceSpan> traces = parseQueryResults(result);
            log.info("Query returned {} traces", traces.size());

            return traces;
        } catch (Exception e) {
            log.error("Error querying Azure Monitor", e);
            throw new RuntimeException("Failed to query Azure Monitor: " + e.getMessage());
        }
    }

    @Override
    public List<TraceSpan> queryTraces(long durationThresholdMs, String timeRange,
                                       String resourceGroup, String instrumentationKey,
                                       String cloudRoleName) {
        String baseQuery = buildKustoQuery(durationThresholdMs, timeRange);
        String filterClause = buildFilterClause(resourceGroup, instrumentationKey, cloudRoleName);

        String query = baseQuery.replace("AppDependencies", "AppDependencies" + filterClause);

        log.info("Executing filtered query with RG: '{}', Key: '{}', Role: '{}'",
                resourceGroup, instrumentationKey, cloudRoleName);
        log.info("Full Query:\n{}", query);

        QueryTimeInterval interval = parseTimeRange(timeRange);

        try {
            LogsQueryResult result = logsQueryClient.queryWorkspace(workspaceId, query, interval);
            List<TraceSpan> traces = parseQueryResults(result);
            log.info("Query returned {} traces", traces.size());
            return traces;
        } catch (Exception e) {
            log.error("Error querying Azure Monitor", e);
            throw new RuntimeException("Failed to query Azure Monitor: " + e.getMessage());
        }
    }

    @Override
    public List<TraceSpan> queryTracesWithAllFilters(
            long durationThresholdMs,
            String timeRange,
            String resourceGroup,
            String instrumentationKey,
            String cloudRoleName,
            String className,
            String packageName,
            boolean includeSubPackages) {

        String query = buildCompleteFilteredQuery(
                durationThresholdMs, timeRange, resourceGroup,
                instrumentationKey, cloudRoleName, className,
                packageName, includeSubPackages
        );

        log.info("Executing complete filtered query:");
        log.info("Filters - RG: '{}', CloudRole: '{}', Class: '{}', Package: '{}'",
                resourceGroup, cloudRoleName, className, packageName);
        log.info("Full Query:\n{}", query);

        QueryTimeInterval interval = parseTimeRange(timeRange);

        try {
            LogsQueryResult result = logsQueryClient.queryWorkspace(workspaceId, query, interval);
            List<TraceSpan> traces = parseQueryResults(result);
            log.info("Query returned {} traces", traces.size());
            return traces;
        } catch (Exception e) {
            log.error("Error querying Azure Monitor", e);
            throw new RuntimeException("Failed to query Azure Monitor: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> calculateStatisticsFromTraces(List<TraceSpan> traces) {
        Map<String, Object> stats = new HashMap<>();

        if (traces.isEmpty()) {
            stats.put("totalCount", 0L);
            stats.put("avgDuration", 0.0);
            stats.put("maxDuration", 0.0);
            stats.put("minDuration", 0.0);
            stats.put("errorCount", 0L);
            stats.put("percentile50", 0.0);
            stats.put("percentile75", 0.0);
            stats.put("percentile90", 0.0);
            stats.put("percentile95", 0.0);
            stats.put("percentile99", 0.0);
            return stats;
        }

        DoubleSummaryStatistics durationStats = traces.stream()
                .mapToDouble(TraceSpan::getDurationMs)
                .summaryStatistics();

        stats.put("totalCount", (long) traces.size());
        stats.put("avgDuration", durationStats.getAverage());
        stats.put("maxDuration", durationStats.getMax());
        stats.put("minDuration", durationStats.getMin());

        long errorCount = traces.stream()
                .filter(t -> "False".equalsIgnoreCase(String.valueOf(t.getAttributes().get("success"))) ||
                        (t.getStatus() != null && !t.getStatus().startsWith("2") && !t.getStatus().equals("0")))
                .count();
        stats.put("errorCount", errorCount);

        List<Double> sortedDurations = traces.stream()
                .map(TraceSpan::getDurationMs)
                .sorted()
                .collect(Collectors.toList());

        stats.put("percentile50", calculatePercentileFromSortedList(sortedDurations, 50));
        stats.put("percentile75", calculatePercentileFromSortedList(sortedDurations, 75));
        stats.put("percentile90", calculatePercentileFromSortedList(sortedDurations, 90));
        stats.put("percentile95", calculatePercentileFromSortedList(sortedDurations, 95));
        stats.put("percentile99", calculatePercentileFromSortedList(sortedDurations, 99));

        return stats;
    }

    private double calculatePercentileFromSortedList(List<Double> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) return 0;
        if (sortedValues.size() == 1) return sortedValues.get(0);

        double position = (percentile / 100.0) * (sortedValues.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);

        if (lower == upper) {
            return sortedValues.get(lower);
        }

        double lowerValue = sortedValues.get(lower);
        double upperValue = sortedValues.get(upper);
        double fraction = position - lower;

        return lowerValue + fraction * (upperValue - lowerValue);
    }

    @Override
    public List<TraceSpan> queryErrorTraces(String timeRange) {
        String query = String.format("""
        AppDependencies
        | where TimeGenerated > ago(%s)
        | where Success == "False" or (isnotempty(ResultCode) and ResultCode !startswith "2")
        | extend duration_numeric = todouble(DurationMs)
        | project TimeGenerated, Name, duration_numeric, DurationMs, ResultCode, 
                 Success, DependencyType, Target, Data, AppRoleName, AppRoleInstance,
                 OperationId, Id, ParentId, OperationName, Properties
        | order by TimeGenerated desc
        | limit 1000
        """, timeRange);

        QueryTimeInterval interval = parseTimeRange(timeRange);

        try {
            LogsQueryResult result = logsQueryClient.queryWorkspace(
                    workspaceId,
                    query,
                    interval
            );

            return parseQueryResults(result);
        } catch (Exception e) {
            log.error("Error querying error traces", e);
            throw new RuntimeException("Failed to query error traces: " + e.getMessage());
        }
    }

    @Override
    public List<TraceSpan> queryErrorTraces(String timeRange, String resourceGroup,
                                            String instrumentationKey, String cloudRoleName) {
        String filterClause = buildFilterClause(resourceGroup, instrumentationKey, cloudRoleName);

        String query = String.format("""
        AppDependencies%s
        | where Success == "False" or (isnotempty(ResultCode) and ResultCode !startswith "2")
        | extend duration_numeric = todouble(DurationMs)
        | project TimeGenerated, Name, duration_numeric, DurationMs, ResultCode, 
                 Success, DependencyType, Target, Data, AppRoleName, AppRoleInstance,
                 OperationId, Id, ParentId, OperationName, Properties
        | order by TimeGenerated desc
        | limit 1000
        """, filterClause);

        QueryTimeInterval interval = parseTimeRange(timeRange);

        try {
            LogsQueryResult result = logsQueryClient.queryWorkspace(
                    workspaceId,
                    query,
                    interval
            );

            return parseQueryResults(result);
        } catch (Exception e) {
            log.error("Error querying error traces", e);
            throw new RuntimeException("Failed to query error traces: " + e.getMessage());
        }
    }

    @Override
    public List<TraceSpan> queryMethodsWithErrors(String timeRange) {
        String query = String.format("""
        AppDependencies
        | extend duration_numeric = todouble(DurationMs)
        | where Success == "False" 
           or Properties contains '"error":"true"'
           or Properties contains '"error":true'
           or Properties contains '"custom":{"error":"true"'
           or Properties contains '"custom":{"error":true'
           or Properties contains '"ai.operation.isSuccessful":"false"'
           or Properties contains 'Exception'
           or Properties contains 'exception'
           or Properties contains 'Error'
           or Properties contains 'Failed'
           or Properties contains 'error_message'
           or Name contains 'Error'
           or Name contains 'Exception'
           or Name contains 'Failed'
           or (DependencyType == "HTTP" and (
               ResultCode == "400" or 
               ResultCode == "401" or 
               ResultCode == "403" or 
               ResultCode == "404" or 
               ResultCode == "500" or 
               ResultCode == "502" or 
               ResultCode == "503"
           ))
           or (isnotempty(ResultCode) and ResultCode !startswith "2" and ResultCode != "0" and ResultCode != "")
        | project TimeGenerated, Name, duration_numeric, DurationMs, ResultCode, 
                 Success, DependencyType, Target, Data, AppRoleName, 
                 AppRoleInstance, OperationId, Id, ParentId, OperationName, Properties
        | order by TimeGenerated desc
        | limit 1000
        """ ,timeRange);

        QueryTimeInterval interval = parseTimeRange(timeRange);

        try {
            LogsQueryResult result = logsQueryClient.queryWorkspace(
                    workspaceId,
                    query,
                    interval
            );

            List<TraceSpan> errorTraces = parseQueryResults(result);

            List<TraceSpan> filteredTraces = errorTraces.stream()
                    .filter(trace -> {
                        if ("False".equalsIgnoreCase(String.valueOf(trace.getAttributes().get("success")))) {
                            return true;
                        }

                        String properties = (String) trace.getAttributes().get("properties");
                        if (properties != null) {
                            if (properties.contains("\"error\":\"true\"") ||
                                    properties.contains("\"error\":true") ||
                                    properties.contains("\"custom\":{\"error\":\"true\"") ||
                                    properties.contains("\"custom\":{\"error\":true")) {
                                return true;
                            }
                        }

                        String status = trace.getStatus();
                        if (status != null && !status.isEmpty() &&
                                !status.startsWith("2") && !status.equals("0")) {
                            return true;
                        }

                        return false;
                    })
                    .collect(Collectors.toList());

            log.info("Found {} traces with errors (including custom error=true)", filteredTraces.size());
            return filteredTraces;
        } catch (Exception e) {
            log.error("Error querying methods with errors", e);
            throw new RuntimeException("Failed to query error methods: " + e.getMessage());
        }
    }

    @Override
    public List<TraceSpan> queryMethodsWithErrors(String timeRange, String resourceGroup,
                                                  String instrumentationKey, String cloudRoleName) {
        String filterClause = buildFilterClause(resourceGroup, instrumentationKey, cloudRoleName);

        String query = String.format("""
        AppDependencies%s
        | where Success == "False" or (Properties contains "error" or Properties contains "exception")
        | where Name !startswith "GET" and Name !startswith "POST" and Name !startswith "PUT" and Name !startswith "DELETE"
        | extend duration_numeric = todouble(DurationMs)
        | project TimeGenerated, Name, duration_numeric, DurationMs, ResultCode, 
                 Success, DependencyType, Target, Data, AppRoleName, AppRoleInstance,
                 OperationId, Id, ParentId, OperationName, Properties
        | order by TimeGenerated desc
        | limit 1000
        """, filterClause);

        QueryTimeInterval interval = parseTimeRange(timeRange);

        try {
            LogsQueryResult result = logsQueryClient.queryWorkspace(
                    workspaceId,
                    query,
                    interval
            );

            return parseQueryResults(result);
        } catch (Exception e) {
            log.error("Error querying methods with errors", e);
            throw new RuntimeException("Failed to query methods with errors: " + e.getMessage());
        }
    }

    @Override
    public List<TraceSpan> querySlowOperations(String timeRange, long slowThresholdMs) {
        String query = String.format("""
        AppDependencies
        | where TimeGenerated > ago(%s)
        | extend duration_numeric = todouble(DurationMs)
        | where duration_numeric > %d
        | project TimeGenerated, Name, duration_numeric, DurationMs, ResultCode, 
                 Success, DependencyType, Target, Data, AppRoleName, 
                 AppRoleInstance, OperationId, Id, ParentId, OperationName, Properties
        | order by duration_numeric desc
        | limit 500
        """, timeRange, slowThresholdMs);

        QueryTimeInterval interval = parseTimeRange(timeRange);

        try {
            LogsQueryResult result = logsQueryClient.queryWorkspace(
                    workspaceId,
                    query,
                    interval
            );

            return parseQueryResults(result);
        } catch (Exception e) {
            log.error("Error querying slow operations", e);
            throw new RuntimeException("Failed to query slow operations: " + e.getMessage());
        }
    }

    @Override
    public Map<String, ErrorMethodInfo> analyzeErrorMethods(String timeRange) {
        List<TraceSpan> errorTraces = queryMethodsWithErrors(timeRange);

        Map<String, List<TraceSpan>> errorsByMethod = errorTraces.stream()
                .collect(Collectors.groupingBy(t -> normalizeMethodName(t.getOperationName())));

        Map<String, ErrorMethodInfo> errorAnalysis = new HashMap<>();

        for (Map.Entry<String, List<TraceSpan>> entry : errorsByMethod.entrySet()) {
            String methodName = entry.getKey();
            List<TraceSpan> methodErrors = entry.getValue();

            Map<String, Integer> errorTypes = new HashMap<>();
            List<String> errorMessages = new ArrayList<>();

            for (TraceSpan trace : methodErrors) {
                String properties = (String) trace.getAttributes().get("properties");
                if (properties != null) {
                    extractErrorInfo(properties, errorTypes, errorMessages);
                }

                String resultCode = trace.getStatus();
                if (resultCode != null && !resultCode.isEmpty()) {
                    errorTypes.merge("HTTP " + resultCode, 1, Integer::sum);
                }
            }

            ErrorMethodInfo info = ErrorMethodInfo.builder()
                    .methodName(methodName)
                    .errorCount(methodErrors.size())
                    .errorTypes(errorTypes)
                    .sampleMessages(errorMessages.stream().distinct().limit(5).collect(Collectors.toList()))
                    .avgDuration(methodErrors.stream().mapToDouble(TraceSpan::getDurationMs).average().orElse(0))
                    .lastErrorTime(methodErrors.stream()
                            .map(TraceSpan::getTimestamp)
                            .max(LocalDateTime::compareTo)
                            .orElse(null))
                    .build();

            errorAnalysis.put(methodName, info);
        }

        return errorAnalysis;
    }

    @Override
    public Map<String, Object> getTraceStatistics(String timeRange) {
        String query = String.format("""
        AppDependencies
        | where TimeGenerated > ago(%s)
        | extend duration_numeric = todouble(DurationMs)
        | summarize 
            totalCount = count(),
            avgDuration = avg(duration_numeric),
            maxDuration = max(duration_numeric),
            minDuration = min(duration_numeric),
            errorCount = countif(Success == "False"),
            percentile95 = percentile(duration_numeric, 95),
            percentile99 = percentile(duration_numeric, 99)
        """, timeRange);

        QueryTimeInterval interval = parseTimeRange(timeRange);

        try {
            LogsQueryResult result = logsQueryClient.queryWorkspace(
                    workspaceId,
                    query,
                    interval
            );

            return parseStatistics(result);
        } catch (Exception e) {
            log.error("Error getting statistics", e);
            return new HashMap<>();
        }
    }

    @Override
    public Map<String, Object> getTraceStatistics(String timeRange, String resourceGroup,
                                                  String instrumentationKey, String cloudRoleName) {
        String filterClause = buildFilterClause(resourceGroup, instrumentationKey, cloudRoleName);

        String query = String.format("""
        AppDependencies%s
        | summarize 
            TotalCount = count(),
            AvgDuration = avg(DurationMs),
            MaxDuration = max(DurationMs),
            MinDuration = min(DurationMs),
            Percentile95 = percentile(DurationMs, 95),
            Percentile99 = percentile(DurationMs, 99),
            ErrorCount = countif(Success == "False")
        """, filterClause);

        QueryTimeInterval interval = parseTimeRange(timeRange);

        try {
            LogsQueryResult result = logsQueryClient.queryWorkspace(workspaceId, query, interval);

            Map<String, Object> stats = new HashMap<>();
            if (result.getAllTables() != null && !result.getAllTables().isEmpty()) {
                LogsTable table = result.getAllTables().get(0);
                if (!table.getRows().isEmpty()) {
                    LogsTableRow row = table.getRows().get(0);
                    stats.put("totalCount", getLongValue(row, 0));
                    stats.put("avgDuration", getDoubleValue(row, 1));
                    stats.put("maxDuration", getDoubleValue(row, 2));
                    stats.put("minDuration", getDoubleValue(row, 3));
                    stats.put("percentile95", getDoubleValue(row, 4));
                    stats.put("percentile99", getDoubleValue(row, 5));
                    stats.put("errorCount", getLongValue(row, 6));
                }
            }
            return stats;
        } catch (Exception e) {
            log.error("Error getting trace statistics", e);
            return new HashMap<>();
        }
    }

    @Override
    public List<TraceSpan> queryErrorTracesWithAllFilters(
            String timeRange, String resourceGroup,
            String instrumentationKey, String cloudRoleName,
            String className, String packageName,
            boolean includeSubPackages) {

        StringBuilder query = new StringBuilder();

        query.append("AppDependencies\n");
        query.append(String.format("| where TimeGenerated > ago(%s)\n", timeRange));

        if (resourceGroup != null && !resourceGroup.isEmpty()) {
            query.append(String.format("| where _ResourceId contains \"/resourceGroups/%s/\"\n",
                    resourceGroup.toLowerCase()));
        }

        if (cloudRoleName != null && !cloudRoleName.isEmpty()) {
            query.append(String.format("| where AppRoleName == '%s'\n", cloudRoleName));
        }

        if (packageName != null && !packageName.isEmpty()) {
            if (includeSubPackages) {
                query.append(String.format("| where Name startswith '%s.'\n", packageName));
            } else {
                query.append(String.format(
                        "| where Name startswith '%s.' and not(Name matches regex '%s\\\\.[^.]+\\\\.')\n",
                        packageName, packageName.replace(".", "\\\\.")));
            }
        }

        if (className != null && !className.isEmpty()) {
            if (className.contains(".")) {
                query.append(String.format("| where Name contains '%s'\n", className));
            } else {
                query.append(String.format("| where Name matches regex '\\\\.%s(\\\\.|$)'\n", className));
            }
        }

        query.append("| where Success == \"False\" \n");
        query.append("   or Properties contains '\"error\":\"true\"'\n");
        query.append("   or Properties contains '\"error\":true'\n");
        query.append("   or Properties contains 'Exception'\n");
        query.append("   or Properties contains 'exception'\n");
        query.append("   or (isnotempty(ResultCode) and ResultCode !startswith \"2\" and ResultCode != \"0\")\n");

        query.append("| where Name !startswith 'GET' and Name !startswith 'POST'\n");

        query.append("| extend duration_numeric = todouble(DurationMs)\n");
        query.append("| project TimeGenerated, Name, duration_numeric, DurationMs, ResultCode, \n");
        query.append("         Success, DependencyType, Target, Data, AppRoleName, \n");
        query.append("         AppRoleInstance, OperationId, Id, ParentId, OperationName, Properties\n");
        query.append("| order by TimeGenerated desc\n");
        query.append("| limit 1000");

        log.info("Executing error query with all filters:\n{}", query);

        QueryTimeInterval interval = parseTimeRange(timeRange);

        try {
            LogsQueryResult result = logsQueryClient.queryWorkspace(workspaceId, query.toString(), interval);
            return parseQueryResults(result);
        } catch (Exception e) {
            log.error("Error querying error traces", e);
            throw new RuntimeException("Failed to query error traces: " + e.getMessage());
        }
    }

    @Override
    public Map<String, ErrorMethodInfo> analyzeErrorMethodsByClass(String timeRange, String className, 
                                                                   String packageName, boolean includeSubPackages) {
        List<TraceSpan> allErrorTraces = queryMethodsWithErrors(timeRange);
        List<TraceSpan> filteredTraces = filterTracesByClassOrPackage(allErrorTraces, className, packageName, includeSubPackages);

        Map<String, List<TraceSpan>> errorsByMethod = filteredTraces.stream()
                .collect(Collectors.groupingBy(trace -> normalizeOperationName(trace.getOperationName())));

        Map<String, ErrorMethodInfo> result = new HashMap<>();

        for (Map.Entry<String, List<TraceSpan>> entry : errorsByMethod.entrySet()) {
            String methodName = entry.getKey();
            List<TraceSpan> methodErrors = entry.getValue();

            Map<String, Integer> errorTypes = new HashMap<>();
            List<String> sampleMessages = new ArrayList<>();

            for (TraceSpan trace : methodErrors) {
                String properties = (String) trace.getAttributes().get("properties");
                if (properties != null) {
                    extractErrorInfo(properties, errorTypes, sampleMessages);
                }
            }

            double avgDuration = methodErrors.stream()
                    .mapToDouble(TraceSpan::getDurationMs)
                    .average()
                    .orElse(0);

            LocalDateTime lastError = methodErrors.stream()
                    .map(TraceSpan::getTimestamp)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);

            ErrorMethodInfo info = ErrorMethodInfo.builder()
                    .methodName(methodName)
                    .errorCount(methodErrors.size())
                    .errorTypes(errorTypes)
                    .sampleMessages(sampleMessages.stream().limit(3).collect(Collectors.toList()))
                    .avgDuration(avgDuration)
                    .lastErrorTime(lastError)
                    .build();

            result.put(methodName, info);
        }

        return result;
    }

    @Override
    public List<TraceSpan> queryMethodsWithErrorsByClass(String timeRange, String className, 
                                                         String packageName, boolean includeSubPackages) {
        List<TraceSpan> allErrorTraces = queryMethodsWithErrors(timeRange);
        return filterTracesByClassOrPackage(allErrorTraces, className, packageName, includeSubPackages);
    }

    @Override
    public List<TraceSpan> queryTracesByClass(Long durationThresholdMs, String timeRange,
                                              String className, String packageName,
                                              boolean includeSubPackages) {
        List<TraceSpan> allTraces = queryTraces(durationThresholdMs, timeRange);
        return filterTracesByClassOrPackage(allTraces, className, packageName, includeSubPackages);
    }

    @Override
    public List<TraceSpan> queryTracesByClass(Long durationThresholdMs, String timeRange,
                                              String className, String packageName,
                                              boolean includeSubPackages,
                                              String resourceGroup, String instrumentationKey,
                                              String cloudRoleName) {
        List<TraceSpan> traces;
        if (resourceGroup != null || instrumentationKey != null || cloudRoleName != null) {
            traces = queryTraces(durationThresholdMs, timeRange, resourceGroup, instrumentationKey, cloudRoleName);
        } else {
            traces = queryTraces(durationThresholdMs, timeRange);
        }

        return filterTracesByClassOrPackage(traces, className, packageName, includeSubPackages);
    }

    @Override
    public Set<String> getAllClassNames(String timeRange) {
        List<TraceSpan> traces = queryTraces(0, timeRange);
        return traces.stream()
                .map(trace -> extractClassName(trace.getOperationName()))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> getAllPackageNames(String timeRange) {
        List<TraceSpan> traces = queryTraces(0, timeRange);
        return traces.stream()
                .map(trace -> extractPackageName(trace.getOperationName()))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> getErrorClassNames(String timeRange) {
        List<TraceSpan> errorTraces = queryMethodsWithErrors(timeRange);
        return errorTraces.stream()
                .map(trace -> extractClassName(trace.getOperationName()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> getErrorPackageNames(String timeRange) {
        List<TraceSpan> errorTraces = queryMethodsWithErrors(timeRange);
        return errorTraces.stream()
                .map(trace -> extractPackageName(trace.getOperationName()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    @Override
    public Map<String, ErrorClassStatistics> getErrorStatisticsByClass(String timeRange) {
        List<TraceSpan> errorTraces = queryMethodsWithErrors(timeRange);

        Map<String, List<TraceSpan>> tracesByClass = errorTraces.stream()
                .collect(Collectors.groupingBy(trace -> {
                    String className = extractClassName(trace.getOperationName());
                    return className != null ? className : "Unknown";
                }));

        Map<String, ErrorClassStatistics> statistics = new HashMap<>();

        for (Map.Entry<String, List<TraceSpan>> entry : tracesByClass.entrySet()) {
            String className = entry.getKey();
            List<TraceSpan> classTraces = entry.getValue();

            Set<String> uniqueMethods = classTraces.stream()
                    .map(trace -> normalizeMethodName(trace.getOperationName()))
                    .collect(Collectors.toSet());

            Map<String, Integer> errorTypes = new HashMap<>();
            for (TraceSpan trace : classTraces) {
                String properties = (String) trace.getAttributes().get("properties");
                if (properties != null) {
                    extractErrorInfo(properties, errorTypes, new ArrayList<>());
                }
            }

            ErrorClassStatistics stats = ErrorClassStatistics.builder()
                    .className(className)
                    .packageName(extractPackageName(className))
                    .totalErrors(classTraces.size())
                    .uniqueMethodsWithErrors(uniqueMethods.size())
                    .errorTypes(errorTypes)
                    .avgDuration(classTraces.stream()
                            .mapToDouble(TraceSpan::getDurationMs)
                            .average()
                            .orElse(0))
                    .build();

            statistics.put(className, stats);
        }

        return statistics;
    }

    @Override
    public Map<String, Object> getClassPerformanceStats(String className, String timeRange) {
        List<TraceSpan> classTraces = queryTracesByClass(0L, timeRange, className, null, false);

        if (classTraces.isEmpty()) {
            return Collections.emptyMap();
        }

        DoubleSummaryStatistics stats = classTraces.stream()
                .mapToDouble(TraceSpan::getDurationMs)
                .summaryStatistics();

        Map<String, Object> result = new HashMap<>();
        result.put("className", className);
        result.put("traceCount", classTraces.size());
        result.put("avgDuration", stats.getAverage());
        result.put("minDuration", stats.getMin());
        result.put("maxDuration", stats.getMax());
        result.put("totalDuration", stats.getSum());

        return result;
    }

    @Override
    public Map<String, Object> getPackagePerformanceStats(String packageName, String timeRange,
                                                          boolean includeSubPackages) {
        List<TraceSpan> packageTraces = queryTracesByClass(0L, timeRange, null, packageName, includeSubPackages);

        if (packageTraces.isEmpty()) {
            return Collections.emptyMap();
        }

        DoubleSummaryStatistics stats = packageTraces.stream()
                .mapToDouble(TraceSpan::getDurationMs)
                .summaryStatistics();

        Map<String, Object> result = new HashMap<>();
        result.put("packageName", packageName);
        result.put("includeSubPackages", includeSubPackages);
        result.put("traceCount", packageTraces.size());
        result.put("avgDuration", stats.getAverage());
        result.put("minDuration", stats.getMin());
        result.put("maxDuration", stats.getMax());
        result.put("totalDuration", stats.getSum());

        return result;
    }

    // Private helper methods

    private String buildKustoQuery(long durationThresholdMs, String timeRange) {
        return String.format("""
        AppDependencies
        | where TimeGenerated > ago(%s)
        | where todouble(DurationMs) > %d
        | where Name !startswith "GET /"
        | where Name !startswith "POST /"
        | where Name !startswith "PUT /"
        | where Name !startswith "DELETE /"
        | where Name !startswith "HTTP"
        | where (Name contains "com." or Name contains "org." or Name contains "net." or Name contains "io.")
        | extend duration_numeric = todouble(DurationMs)
        | project TimeGenerated, Name, duration_numeric, DurationMs, ResultCode, 
                 Success, DependencyType, Target, Data, AppRoleName, 
                 AppRoleInstance, OperationId, Id, ParentId, OperationName, Properties
        | top 1000 by duration_numeric desc
        """, timeRange, durationThresholdMs);
    }

    private String buildCompleteFilteredQuery(
            long durationThresholdMs,
            String timeRange,
            String resourceGroup,
            String instrumentationKey,
            String cloudRoleName,
            String className,
            String packageName,
            boolean includeSubPackages) {

        StringBuilder query = new StringBuilder();

        query.append("AppDependencies\n");
        query.append(String.format("| where TimeGenerated > ago(%s)\n", timeRange));

        if (resourceGroup != null && !resourceGroup.isEmpty()) {
            query.append(String.format("| where _ResourceId contains \"/resourceGroups/%s/\"\n",
                    resourceGroup.toLowerCase()));
        }

        if (cloudRoleName != null && !cloudRoleName.isEmpty()) {
            query.append(String.format("| where AppRoleName == '%s'\n", cloudRoleName));
        }

        if (instrumentationKey != null && !instrumentationKey.isEmpty()) {
            log.debug("Skipping instrumentation key filter - not supported in AppDependencies");
        }

        if (packageName != null && !packageName.isEmpty()) {
            if (includeSubPackages) {
                query.append(String.format("| where Name startswith '%s.'\n", packageName));
            } else {
                query.append(String.format(
                        "| where Name startswith '%s.' and not(Name matches regex '%s\\\\.[^.]+\\\\.')\n",
                        packageName, packageName.replace(".", "\\\\.")));
            }
        }

        if (className != null && !className.isEmpty()) {
            if (className.contains(".")) {
                query.append(String.format("| where Name contains '%s'\n", className));
            } else {
                query.append(String.format("| where Name matches regex '\\\\.%s(\\\\.|$)'\n", className));
            }
        }

        query.append(String.format("| where todouble(DurationMs) > %d\n", durationThresholdMs));

        query.append("| where Name !startswith 'GET /'\n");
        query.append("| where Name !startswith 'POST /'\n");
        query.append("| where Name !startswith 'PUT /'\n");
        query.append("| where Name !startswith 'DELETE /'\n");
        query.append("| where Name !startswith 'HTTP'\n");

        query.append("| where (Name contains 'com.' or Name contains 'org.' or Name contains 'net.' or Name contains 'io.')\n");

        query.append("| extend duration_numeric = todouble(DurationMs)\n");
        query.append("| project TimeGenerated, Name, duration_numeric, DurationMs, ResultCode, \n");
        query.append("         Success, DependencyType, Target, Data, AppRoleName, \n");
        query.append("         AppRoleInstance, OperationId, Id, ParentId, OperationName, Properties\n");
        query.append("| top 1000 by duration_numeric desc");

        return query.toString();
    }

    private String buildFilterClause(String resourceGroup, String instrumentationKey, String cloudRoleName) {
        List<String> filters = new ArrayList<>();

        if (resourceGroup != null && !resourceGroup.isEmpty()) {
            filters.add(String.format("_ResourceId contains \"/resourcegroups/%s/\"", resourceGroup.toLowerCase()));
            log.info("Adding resource group filter for: {}", resourceGroup);
        }

        if (instrumentationKey != null && !instrumentationKey.isEmpty()) {
            filters.add(String.format("(_ResourceId contains '%s' or Properties contains '%s')",
                    instrumentationKey, instrumentationKey));
            log.info("Adding instrumentation key filter: {}", instrumentationKey);
        }

        if (cloudRoleName != null && !cloudRoleName.isEmpty()) {
            filters.add(String.format("AppRoleName == '%s'", cloudRoleName));
            log.info("Adding cloud role filter: {}", cloudRoleName);
        }

        String filterClause = filters.isEmpty() ? "" : "\n| where " + String.join(" and ", filters);
        log.info("Generated filter clause: {}", filterClause);

        return filterClause;
    }

    private QueryTimeInterval parseTimeRange(String timeRange) {
        OffsetDateTime endTime = OffsetDateTime.now();
        OffsetDateTime startTime;

        switch (timeRange) {
            case "15m" -> startTime = endTime.minusMinutes(15);
            case "30m" -> startTime = endTime.minusMinutes(30);
            case "1h" -> startTime = endTime.minusHours(1);
            case "3h" -> startTime = endTime.minusHours(3);
            case "6h" -> startTime = endTime.minusHours(6);
            case "12h" -> startTime = endTime.minusHours(12);
            case "24h" -> startTime = endTime.minusDays(1);
            case "3d" -> startTime = endTime.minusDays(3);
            case "7d" -> startTime = endTime.minusDays(7);
            case "30d" -> startTime = endTime.minusDays(30);
            default -> startTime = endTime.minusHours(1);
        }

        return new QueryTimeInterval(startTime, endTime);
    }

    private List<TraceSpan> parseQueryResults(LogsQueryResult result) {
        List<TraceSpan> traces = new ArrayList<>();

        if (result.getAllTables() != null && !result.getAllTables().isEmpty()) {
            LogsTable table = result.getAllTables().get(0);

            List<LogsTableColumn> columns = table.getColumns();

            if (log.isDebugEnabled()) {
                for (int i = 0; i < columns.size(); i++) {
                    log.debug("Column[{}]: {}", i, columns.get(i).getColumnName());
                }
            }

            for (LogsTableRow row : table.getRows()) {
                try {
                    List<LogsTableCell> cells = row.getRow();

                    LocalDateTime timestamp = parseTimestamp(cells, 0);
                    String name = getCellStringValue(cells, 1);
                    double durationMs = getCellDoubleValue(cells, 2);
                    String status = getCellStringValue(cells, 4);
                    String success = getCellStringValue(cells, 5);
                    String dependencyType = getCellStringValue(cells, 6);
                    String target = getCellStringValue(cells, 7);
                    String data = getCellStringValue(cells, 8);
                    String appRoleName = getCellStringValue(cells, 9);
                    String appRoleInstance = getCellStringValue(cells, 10);
                    String operationId = getCellStringValue(cells, 11);
                    String id = getCellStringValue(cells, 12);
                    String parentId = getCellStringValue(cells, 13);
                    String operationName = getCellStringValue(cells, 14);
                    String properties = getCellStringValue(cells, 15);

                    if (name == null || name.isEmpty()) {
                        name = operationName;
                    }

                    Map<String, Object> attributes = new HashMap<>();
                    attributes.put("type", dependencyType);
                    attributes.put("target", target);
                    attributes.put("data", data);
                    attributes.put("success", success);
                    attributes.put("operationContext", operationName);
                    if (properties != null && !properties.isEmpty() && !properties.equals("None")) {
                        attributes.put("properties", properties);
                    }

                    TraceSpan span = TraceSpan.builder()
                            .timestamp(timestamp)
                            .operationName(name)
                            .durationMs(durationMs)
                            .status(status)
                            .cloudRoleName(appRoleName)
                            .cloudRoleInstance(appRoleInstance)
                            .traceId(operationId)
                            .spanId(id)
                            .parentSpanId(parentId)
                            .attributes(attributes)
                            .build();

                    traces.add(span);

                    if (log.isDebugEnabled()) {
                        log.debug("Parsed span: {} with duration {}ms", name, durationMs);
                    }

                } catch (Exception e) {
                    log.warn("Error parsing row: {}", e.getMessage());
                    if (log.isDebugEnabled()) {
                        log.debug("Failed row data: {}", row.getRow(), e);
                    }
                }
            }
        }

        return traces;
    }

    @Override
    public Map<String, Object> queryPackageMetricsForAlerts(
            String cloudRoleName,
            String packageName,
            String timeRange,
            Long durationThresholdMs,
            GitHubProperties.SLAConfig slaConfig) {

        StringBuilder whereClause = new StringBuilder();
        if (cloudRoleName != null && !cloudRoleName.isEmpty()) {
            whereClause.append(String.format("| where AppRoleName == '%s'\n", cloudRoleName));
        }
        if (packageName != null && !packageName.isEmpty()) {
            whereClause.append(String.format("| where Name startswith '%s'\n", packageName));
        }

        String query = String.format("""
        AppDependencies
        | where TimeGenerated > ago(%s)
        %s
        | summarize 
            TotalCount = count(),
            ErrorCount = countif(Success == "False"),
            AvgDuration = avg(todouble(DurationMs)),
            P%d = percentile(todouble(DurationMs), %d),
            P99 = percentile(todouble(DurationMs), 99),
            MaxDuration = max(todouble(DurationMs)),
            SlowRequests_Critical = countif(todouble(DurationMs) > %d),
            SlowRequests_High = countif(todouble(DurationMs) > %d),
            SlowRequests_Medium = countif(todouble(DurationMs) > %d)
        """,
                timeRange,
                whereClause.toString(),
                slaConfig.getPercentile(),
                slaConfig.getPercentile(),
                slaConfig.getCriticalDurationMs(),
                slaConfig.getHighDurationMs(),
                durationThresholdMs
        );

        try {
            LogsQueryResult result = logsQueryClient.queryWorkspace(
                    workspaceId,
                    query,
                    new QueryTimeInterval(Duration.ofHours(1))
            );

            Map<String, Object> metrics = new HashMap<>();
            if (result.getTable() != null && !result.getTable().getRows().isEmpty()) {
                LogsTable table = result.getTable();
                LogsTableRow row = table.getRows().get(0);
                Map<String, Integer> columnIndices = buildColumnIndicesMap(table);

                metrics.put("TotalCount", getValueAsLong(row, columnIndices, "TotalCount"));
                metrics.put("ErrorCount", getValueAsLong(row, columnIndices, "ErrorCount"));
                metrics.put("AvgDuration", getValueAsDouble(row, columnIndices, "AvgDuration"));
                metrics.put("percentile" + slaConfig.getPercentile(),
                        getValueAsDouble(row, columnIndices, "P" + slaConfig.getPercentile()));
                metrics.put("percentile99", getValueAsDouble(row, columnIndices, "P99"));
                metrics.put("MaxDuration", getValueAsDouble(row, columnIndices, "MaxDuration"));
            }
            return metrics;

        } catch (Exception e) {
            log.error("Error querying package metrics for alerts: {}", e.getMessage(), e);
            return new HashMap<>();
        }
    }

    @Override
    public List<PerformanceHotspot> queryTopSlowOperations(
            String cloudRoleName,
            String packageName,
            String timeRange,
            GitHubProperties.SLAConfig slaConfig,
            int topN) {

        StringBuilder whereClause = new StringBuilder();
        if (cloudRoleName != null && !cloudRoleName.isEmpty()) {
            whereClause.append(String.format("| where AppRoleName == '%s'\n", cloudRoleName));
        }
        if (packageName != null && !packageName.isEmpty()) {
            whereClause.append(String.format("| where Name startswith '%s'\n", packageName));
        }

        String query = String.format("""
        AppDependencies
        | where TimeGenerated > ago(%s)
        %s
        | summarize 
            AvgDuration = avg(todouble(DurationMs)),
            MaxDuration = max(todouble(DurationMs)),
            Count = count(),
            ErrorCount = countif(Success == "False")
            by Name
        | extend ErrorRate = todouble(ErrorCount) / todouble(Count)
        | where AvgDuration > %d or ErrorRate > %f
        | top %d by AvgDuration desc
        """,
                timeRange,
                whereClause.toString(),
                slaConfig.getHighDurationMs(),
                slaConfig.getHighErrorRate(),
                topN
        );

        try {
            LogsQueryResult result = logsQueryClient.queryWorkspace(
                    workspaceId,
                    query,
                    new QueryTimeInterval(Duration.ofHours(1))
            );

            List<PerformanceHotspot> hotspots = new ArrayList<>();
            if (result.getTable() != null) {
                LogsTable table = result.getTable();
                Map<String, Integer> columnIndices = buildColumnIndicesMap(table);

                for (LogsTableRow row : table.getRows()) {
                    String operationName = getValueAsString(row, columnIndices, "Name");
                    Double avgDuration = getValueAsDouble(row, columnIndices, "AvgDuration");
                    Double maxDuration = getValueAsDouble(row, columnIndices, "MaxDuration");
                    Long count = getValueAsLong(row, columnIndices, "Count");
                    Double errorRate = getValueAsDouble(row, columnIndices, "ErrorRate");

                    String severity = "MEDIUM";
                    if (avgDuration != null && errorRate != null) {
                        if (avgDuration > slaConfig.getCriticalDurationMs() ||
                                errorRate > slaConfig.getCriticalErrorRate()) {
                            severity = "CRITICAL";
                        } else if (avgDuration > slaConfig.getHighDurationMs() ||
                                errorRate > slaConfig.getHighErrorRate()) {
                            severity = "HIGH";
                        }
                    }

                    PerformanceHotspot hotspot = PerformanceHotspot.builder()
                            .operation(operationName)
                            .avgDurationMs(avgDuration != null ? avgDuration : 0.0)
                            .maxDurationMs(maxDuration != null ? maxDuration : 0.0)
                            .occurrenceCount(count != null ? count.intValue() : 0)
                            .errorRate(errorRate != null ? errorRate : 0.0)
                            .severity(severity)
                            .build();

                    hotspots.add(hotspot);
                }
            }
            return hotspots;

        } catch (Exception e) {
            log.error("Error querying top slow operations: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @Override
    public TraceSpan getSampleTraceForOperation(
            String operationName,
            String cloudRoleName,
            String timeRange) {

        String query = String.format("""
        AppDependencies
        | where TimeGenerated > ago(%s)
        | where Name == '%s'
        %s
        | extend duration_numeric = todouble(DurationMs)
        | top 1 by duration_numeric desc
        | project 
            TimeGenerated,
            Name,
            DurationMs,
            Success,
            ResultCode,
            AppRoleName,
            AppRoleInstance,
            OperationId,
            Id,
            ParentId
        """,
                timeRange,
                operationName,
                cloudRoleName != null ? String.format("| where AppRoleName == '%s'", cloudRoleName) : ""
        );

        try {
            LogsQueryResult result = logsQueryClient.queryWorkspace(
                    workspaceId,
                    query,
                    new QueryTimeInterval(Duration.ofMinutes(30))
            );

            if (result.getTable() != null && !result.getTable().getRows().isEmpty()) {
                LogsTable table = result.getTable();
                LogsTableRow row = table.getRows().get(0);
                Map<String, Integer> columnIndices = buildColumnIndicesMap(table);

                String timestampStr = getValueAsString(row, columnIndices, "TimeGenerated");
                LocalDateTime timestamp = parseTimestamp(timestampStr);

                Map<String, Object> attributes = new HashMap<>();
                attributes.put("success", getValueAsString(row, columnIndices, "Success"));
                attributes.put("resultCode", getValueAsString(row, columnIndices, "ResultCode"));

                return TraceSpan.builder()
                        .traceId(getValueAsString(row, columnIndices, "OperationId"))
                        .spanId(getValueAsString(row, columnIndices, "Id"))
                        .parentSpanId(getValueAsString(row, columnIndices, "ParentId"))
                        .operationName(getValueAsString(row, columnIndices, "Name"))
                        .cloudRoleName(getValueAsString(row, columnIndices, "AppRoleName"))
                        .cloudRoleInstance(getValueAsString(row, columnIndices, "AppRoleInstance"))
                        .durationMs(getValueAsDouble(row, columnIndices, "DurationMs"))
                        .timestamp(timestamp)
                        .status(getValueAsString(row, columnIndices, "Success").equals("True") ? "OK" : "ERROR")
                        .attributes(attributes)
                        .build();
            }
        } catch (Exception e) {
            log.error("Error getting sample trace for operation: {}", operationName, e);
        }
        return null;
    }

    // HELPER METHODS - ALL OF THEM

    private String buildWhereClause(String cloudRoleName, String packageName) {
        StringBuilder whereClause = new StringBuilder();

        if (cloudRoleName != null && !cloudRoleName.isEmpty()) {
            whereClause.append(String.format("| where cloud_RoleName == '%s'\n", cloudRoleName));
        }

        if (packageName != null && !packageName.isEmpty()) {
            whereClause.append(String.format("| where operation_Name startswith '%s'\n", packageName));
        }

        return whereClause.toString();
    }

    private Map<String, Object> parseMetricsResult(LogsQueryResult result, GitHubProperties.SLAConfig slaConfig) {
        Map<String, Object> metrics = new HashMap<>();

        if (result.getTable() != null && !result.getTable().getRows().isEmpty()) {
            LogsTable table = result.getTable();
            LogsTableRow row = table.getRows().get(0);

            // Get column indices
            Map<String, Integer> columnIndices = buildColumnIndicesMap(table);

            // Extract values
            metrics.put("TotalCount", getValueAsLong(row, columnIndices, "TotalCount"));
            metrics.put("ErrorCount", getValueAsLong(row, columnIndices, "ErrorCount"));
            metrics.put("AvgDuration", getValueAsDouble(row, columnIndices, "AvgDuration"));
            metrics.put("percentile" + slaConfig.getPercentile(),
                    getValueAsDouble(row, columnIndices, "P" + slaConfig.getPercentile()));
            metrics.put("percentile99", getValueAsDouble(row, columnIndices, "P99"));
            metrics.put("MaxDuration", getValueAsDouble(row, columnIndices, "MaxDuration"));
            metrics.put("SlowRequests_Critical", getValueAsLong(row, columnIndices, "SlowRequests_Critical"));
            metrics.put("SlowRequests_High", getValueAsLong(row, columnIndices, "SlowRequests_High"));
            metrics.put("SlowRequests_Medium", getValueAsLong(row, columnIndices, "SlowRequests_Medium"));
        }

        return metrics;
    }

    private List<PerformanceHotspot> parseHotspotsResult(LogsQueryResult result, GitHubProperties.SLAConfig slaConfig) {
        List<PerformanceHotspot> hotspots = new ArrayList<>();

        if (result.getTable() != null) {
            LogsTable table = result.getTable();
            Map<String, Integer> columnIndices = buildColumnIndicesMap(table);

            for (LogsTableRow row : table.getRows()) {
                String operationName = getValueAsString(row, columnIndices, "operation_Name");
                Double avgDuration = getValueAsDouble(row, columnIndices, "AvgDuration");
                Double maxDuration = getValueAsDouble(row, columnIndices, "MaxDuration");
                Long count = getValueAsLong(row, columnIndices, "Count");
                Double errorRate = getValueAsDouble(row, columnIndices, "ErrorRate");

                // Determine severity
                String severity = determineSeverity(avgDuration, errorRate, slaConfig);

                PerformanceHotspot hotspot = PerformanceHotspot.builder()
                        .operation(operationName)
                        .avgDurationMs(avgDuration != null ? avgDuration : 0.0)
                        .maxDurationMs(maxDuration != null ? maxDuration : 0.0)
                        .occurrenceCount(count != null ? count.intValue() : 0)
                        .errorRate(errorRate != null ? errorRate : 0.0)
                        .severity(severity)
                        .build();

                hotspots.add(hotspot);
            }
        }

        return hotspots;
    }

    private TraceSpan mapRowToTraceSpan(LogsTableRow row, LogsTable table) {
        try {
            Map<String, Integer> columnIndices = buildColumnIndicesMap(table);

            // Extract custom dimensions if available
            String customDims = getValueAsString(row, columnIndices, "customDimensions");
            Map<String, String> customDimensions = parseCustomDimensions(customDims);

            // Parse timestamp to LocalDateTime
            String timestampStr = getValueAsString(row, columnIndices, "timestamp");
            LocalDateTime timestamp = null;
            if (timestampStr != null && !timestampStr.isEmpty()) {
                try {
                    timestamp = LocalDateTime.parse(timestampStr.replace(" ", "T"));
                } catch (Exception e) {
                    log.debug("Could not parse timestamp: {}", timestampStr);
                }
            }

            // Build attributes map
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("className", customDimensions.getOrDefault("ClassName", ""));
            attributes.put("methodName", customDimensions.getOrDefault("MethodName", ""));
            attributes.put("success", getValueAsBoolean(row, columnIndices, "success"));
            attributes.put("resultCode", getValueAsString(row, columnIndices, "resultCode"));

            // Determine status based on success field
            Boolean success = getValueAsBoolean(row, columnIndices, "success");
            String status = success ? "OK" : "ERROR";

            return TraceSpan.builder()
                    .traceId(getValueAsString(row, columnIndices, "operation_Id"))
                    .spanId(customDimensions.getOrDefault("SpanId", ""))
                    .parentSpanId(customDimensions.getOrDefault("ParentId", ""))
                    .operationName(getValueAsString(row, columnIndices, "operation_Name"))
                    .cloudRoleName(getValueAsString(row, columnIndices, "cloud_RoleName"))
                    .cloudRoleInstance(getValueAsString(row, columnIndices, "cloud_RoleInstance"))
                    .durationMs(getValueAsDouble(row, columnIndices, "duration"))
                    .timestamp(timestamp)
                    .status(status)
                    .attributes(attributes)
                    .build();
        } catch (Exception e) {
            log.error("Error mapping row to TraceSpan", e);
            return null;
        }
    }
    private Map<String, Integer> buildColumnIndicesMap(LogsTable table) {
        Map<String, Integer> columnIndices = new HashMap<>();
        for (int i = 0; i < table.getColumns().size(); i++) {
            columnIndices.put(table.getColumns().get(i).getColumnName(), i);
        }
        return columnIndices;
    }

    private String getValueAsString(LogsTableRow row, Map<String, Integer> columnIndices, String columnName) {
        Integer index = columnIndices.get(columnName);
        if (index != null && index < row.getRow().size()) {
            Object value = row.getRow().get(index);
            if (value != null) {
                return value.toString();
            }
        }
        return "";
    }

    private Long getValueAsLong(LogsTableRow row, Map<String, Integer> columnIndices, String columnName) {
        Integer index = columnIndices.get(columnName);
        if (index != null && index < row.getRow().size()) {
            Object value = row.getRow().get(index);
            if (value != null && !value.toString().isEmpty()) {
                try {
                    return Long.parseLong(value.toString());
                } catch (NumberFormatException e) {
                    log.debug("Could not parse long value for column {}: {}", columnName, value);
                }
            }
        }
        return 0L;
    }

    private Double getValueAsDouble(LogsTableRow row, Map<String, Integer> columnIndices, String columnName) {
        Integer index = columnIndices.get(columnName);
        if (index != null && index < row.getRow().size()) {
            Object value = row.getRow().get(index);
            if (value != null && !value.toString().isEmpty()) {
                try {
                    return Double.parseDouble(value.toString());
                } catch (NumberFormatException e) {
                    log.debug("Could not parse double value for column {}: {}", columnName, value);
                }
            }
        }
        return 0.0;
    }

    private Boolean getValueAsBoolean(LogsTableRow row, Map<String, Integer> columnIndices, String columnName) {
        Integer index = columnIndices.get(columnName);
        if (index != null && index < row.getRow().size()) {
            Object value = row.getRow().get(index);
            if (value != null) {
                String strValue = value.toString().toLowerCase();
                return "true".equals(strValue) || "1".equals(strValue);
            }
        }
        return false;
    }

    private Map<String, String> parseCustomDimensions(String customDims) {
        Map<String, String> dimensions = new HashMap<>();
        if (customDims != null && !customDims.isEmpty()) {
            try {
                // Parse JSON-like custom dimensions
                // Format: {"key1":"value1","key2":"value2"}
                customDims = customDims.replace("{", "").replace("}", "");
                String[] pairs = customDims.split(",");
                for (String pair : pairs) {
                    String[] keyValue = pair.split(":");
                    if (keyValue.length == 2) {
                        String key = keyValue[0].replace("\"", "").trim();
                        String value = keyValue[1].replace("\"", "").trim();
                        dimensions.put(key, value);
                    }
                }
            } catch (Exception e) {
                log.debug("Could not parse custom dimensions: {}", customDims);
            }
        }
        return dimensions;
    }

    private String determineSeverity(Double avgDuration, Double errorRate, GitHubProperties.SLAConfig slaConfig) {
        if (avgDuration == null) avgDuration = 0.0;
        if (errorRate == null) errorRate = 0.0;

        if (avgDuration > slaConfig.getCriticalDurationMs() ||
                errorRate > slaConfig.getCriticalErrorRate()) {
            return "CRITICAL";
        } else if (avgDuration > slaConfig.getHighDurationMs() ||
                errorRate > slaConfig.getHighErrorRate()) {
            return "HIGH";
        } else {
            return "MEDIUM";
        }
    }

    private Map<String, Object> parseStatistics(LogsQueryResult result) {
        Map<String, Object> stats = new HashMap<>();

        if (result.getAllTables() != null && !result.getAllTables().isEmpty()) {
            LogsTable table = result.getAllTables().get(0);

            if (!table.getRows().isEmpty()) {
                LogsTableRow row = table.getRows().get(0);
                List<LogsTableCell> cells = row.getRow();

                stats.put("totalCount", getCellLongValue(cells, 0));
                stats.put("avgDuration", getCellDoubleValue(cells, 1));
                stats.put("maxDuration", getCellDoubleValue(cells, 2));
                stats.put("minDuration", getCellDoubleValue(cells, 3));
                stats.put("errorCount", getCellLongValue(cells, 4));
                stats.put("percentile95", getCellDoubleValue(cells, 5));
                stats.put("percentile99", getCellDoubleValue(cells, 6));
            }
        }

        return stats;
    }

    private String normalizeMethodName(String operationName) {
        if (operationName == null) return "Unknown";

        if (operationName.contains("$$EnhancerBySpringCGLIB$$")) {
            operationName = operationName.substring(0, operationName.indexOf("$$EnhancerBySpringCGLIB$$"));
        }

        if (operationName.contains(".CGLIB$")) {
            int idx = operationName.lastIndexOf('.');
            if (idx > 0) {
                operationName = operationName.substring(0, idx);
            }
        }

        return operationName;
    }

    private String normalizeOperationName(String operation) {
        if (operation == null) return operation;

        if (operation.contains("$$EnhancerBySpringCGLIB$$")) {
            operation = operation.substring(0, operation.indexOf("$$EnhancerBySpringCGLIB$$"));
        }

        if (operation.contains("CGLIB$")) {
            int cglibIndex = operation.indexOf("CGLIB$");
            if (cglibIndex > 0) {
                operation = operation.substring(0, cglibIndex - 1);
            }
        }

        return operation;
    }



    private void extractErrorInfo(String properties, Map<String, Integer> errorTypes, List<String> errorMessages) {
        try {
            if (properties.contains("\"error\":\"true\"") || properties.contains("\"error\":true")) {
                errorTypes.merge("Custom Error (error=true)", 1, Integer::sum);

                if (properties.contains("\"error_message\":")) {
                    int start = properties.indexOf("\"error_message\":\"") + 17;
                    if (start > 16) {
                        int end = properties.indexOf("\"", start);
                        if (end > start) {
                            String message = properties.substring(start, end);
                            errorMessages.add("Custom Error: " + message);
                        }
                    }
                }
            }

            if (properties.contains("\"custom\":{") &&
                    (properties.contains("\"error\":\"true\"") || properties.contains("\"error\":true"))) {
                errorTypes.merge("Custom Attribute Error", 1, Integer::sum);
            }

            if (properties.contains("Exception") || properties.contains("exception")) {
                errorTypes.merge("Exception Detected", 1, Integer::sum);

                int exceptionIndex = properties.toLowerCase().indexOf("exception");
                if (exceptionIndex > 0) {
                    int start = Math.max(0, exceptionIndex - 50);
                    int end = Math.min(properties.length(), exceptionIndex + 50);
                    String context = properties.substring(start, end);

                    if (context.matches(".*[A-Z][a-zA-Z]*Exception.*")) {
                        errorMessages.add("Exception found in: " + context.trim());
                    }
                }
            }

            if (properties.toLowerCase().contains("error") &&
                    !properties.contains("\"error\":false") &&
                    !properties.contains("\"error\":\"false\"")) {
                errorTypes.merge("Error Pattern Detected", 1, Integer::sum);
            }

            if (properties.contains("Failed") || properties.contains("failed")) {
                errorTypes.merge("Failed Operation", 1, Integer::sum);
            }

            if (properties.contains("\"message\":") &&
                    (properties.toLowerCase().contains("error") || properties.toLowerCase().contains("fail"))) {
                int start = properties.indexOf("\"message\":\"") + 11;
                if (start > 10) {
                    int end = properties.indexOf("\"", start);
                    if (end > start) {
                        String message = properties.substring(start, Math.min(end, start + 200));
                        if (message.toLowerCase().contains("error") || message.toLowerCase().contains("fail")) {
                            errorMessages.add(message);
                        }
                    }
                }
            }

            if (properties.contains("\"ai.operation.isSuccessful\":\"false\"")) {
                errorTypes.merge("Operation Failed (AI Flag)", 1, Integer::sum);
            }
        } catch (Exception e) {
            log.warn("Failed to parse error info from properties: {}", e.getMessage());
        }
    }

    private List<TraceSpan> filterTracesByClassOrPackage(List<TraceSpan> traces, String className, 
                                                         String packageName, boolean includeSubPackages) {
        if (className == null && packageName == null) {
            return traces;
        }

        return traces.stream()
                .filter(trace -> {
                    String operation = normalizeOperationName(trace.getOperationName());

                    if (className != null && !className.isEmpty()) {
                        return matchesClassName(operation, className);
                    }

                    if (packageName != null && !packageName.isEmpty()) {
                        return matchesPackageName(operation, packageName, includeSubPackages);
                    }

                    return true;
                })
                .collect(Collectors.toList());
    }

    private boolean matchesClassName(String operation, String className) {
        if (operation.contains(className)) {
            int index = operation.indexOf(className);
            if (index > 0) {
                char before = operation.charAt(index - 1);
                if (before != '.' && before != '$') {
                    return false;
                }
            }
            if (index + className.length() < operation.length()) {
                char after = operation.charAt(index + className.length());
                if (after != '.' && after != '$' && after != '#') {
                    return false;
                }
            }
            return true;
        }

        if (className.contains(".")) {
            return operation.contains(className);
        }

        return false;
    }

    private boolean matchesPackageName(String operation, String packageName, boolean includeSubPackages) {
        String normalizedPackage = packageName.endsWith(".") ? packageName : packageName + ".";

        if (includeSubPackages) {
            return operation.startsWith(normalizedPackage) || operation.contains("." + normalizedPackage);
        } else {
            if (operation.startsWith(normalizedPackage)) {
                String remainder = operation.substring(normalizedPackage.length());
                int nextDot = remainder.indexOf('.');
                if (nextDot > 0) {
                    String beforeDot = remainder.substring(0, nextDot);
                    return beforeDot.matches(".*[A-Z].*");
                }
                return true;
            }
        }

        return false;
    }

    @Override
    public String extractClassName(String operation) {
        if (operation == null || operation.isEmpty()) {
            return null;
        }

        operation = normalizeOperationName(operation);

        int lastDot = operation.lastIndexOf('.');
        if (lastDot > 0) {
            String possibleMethod = operation.substring(lastDot + 1);
            if (!possibleMethod.isEmpty() && Character.isLowerCase(possibleMethod.charAt(0))) {
                operation = operation.substring(0, lastDot);
            }
        }

        return operation;
    }

    @Override
    public String extractPackageName(String operation) {
        String className = extractClassName(operation);
        if (className == null) {
            return null;
        }

        int lastDot = className.lastIndexOf('.');
        if (lastDot > 0) {
            return className.substring(0, lastDot);
        }

        return null;
    }

    // Helper methods for LogsTableCell
    private String getCellStringValue(List<LogsTableCell> cells, int index) {
        if (index < cells.size() && cells.get(index) != null) {
            try {
                String value = cells.get(index).getValueAsString();
                return (value != null && "None".equals(value)) ? "" : value;
            } catch (Exception e) {
                log.debug("Failed to get string value at index {}: {}", index, e.getMessage());
                return "";
            }
        }
        return "";
    }

    private double getCellDoubleValue(List<LogsTableCell> cells, int index) {
        if (index < cells.size() && cells.get(index) != null) {
            LogsTableCell cell = cells.get(index);
            try {
                Double value = cell.getValueAsDouble();
                return value != null ? value : 0.0;
            } catch (Exception e) {
                try {
                    String strValue = cell.getValueAsString();
                    if (strValue != null && !strValue.isEmpty() && !"None".equals(strValue)) {
                        return Double.parseDouble(strValue);
                    }
                } catch (Exception e2) {
                    log.debug("Failed to parse double at index {}: {}", index, e2.getMessage());
                }
            }
        }
        return 0.0;
    }

    private long getCellLongValue(List<LogsTableCell> cells, int index) {
        if (index < cells.size() && cells.get(index) != null) {
            LogsTableCell cell = cells.get(index);
            try {
                Long value = cell.getValueAsLong();
                return value != null ? value : 0L;
            } catch (Exception e) {
                try {
                    String strValue = cell.getValueAsString();
                    if (strValue != null && !strValue.isEmpty() && !"None".equals(strValue)) {
                        if (strValue.contains(".")) {
                            return (long) Double.parseDouble(strValue);
                        }
                        return Long.parseLong(strValue);
                    }
                } catch (Exception e2) {
                    log.debug("Failed to parse long at index {}: {}", index, e2.getMessage());
                }
            }
        }
        return 0L;
    }

    // Add this helper method if not present
    private LocalDateTime parseTimestamp(String timestampStr) {
        if (timestampStr == null || timestampStr.isEmpty()) return null;
        try {
            if (timestampStr.contains("T")) {
                return LocalDateTime.parse(timestampStr.substring(0, timestampStr.lastIndexOf('.')));
            }
            return LocalDateTime.parse(timestampStr.replace(" ", "T"));
        } catch (Exception e) {
            log.debug("Could not parse timestamp: {}", timestampStr);
            return null;
        }
    }

    private LocalDateTime parseTimestamp(List<LogsTableCell> cells, int index) {
        if (index < cells.size() && cells.get(index) != null) {
            try {
                String timestampStr = cells.get(index).getValueAsString();
                if (timestampStr != null) {
                    if (timestampStr.endsWith("Z")) {
                        timestampStr = timestampStr.substring(0, timestampStr.length() - 1);
                    }
                    if (timestampStr.contains("T")) {
                        return LocalDateTime.parse(timestampStr);
                    } else {
                        return LocalDateTime.parse(timestampStr.replace(" ", "T"));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse timestamp at index {}: {}", index, e.getMessage());
            }
        }
        return LocalDateTime.now();
    }

    private Long getLongValue(LogsTableRow row, int index) {
        try {
            Object value = row.getRow().get(index);
            if (value == null) return 0L;
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            log.warn("Error parsing long value at index {}: {}", index, e.getMessage());
            return 0L;
        }
    }

    private Double getDoubleValue(LogsTableRow row, int index) {
        try {
            Object value = row.getRow().get(index);
            if (value == null) return 0.0;
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            log.warn("Error parsing double value at index {}: {}", index, e.getMessage());
            return 0.0;
        }
    }
}
