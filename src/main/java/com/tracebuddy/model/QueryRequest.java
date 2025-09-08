package com.tracebuddy.model;

import lombok.Data;

@Data
public class QueryRequest {
    private String query;
    private Long durationThresholdMs;
    private String timeRange;
    private String className;
    private String packageName;
    private boolean includeSubPackages = true;
    private String resourceGroup;
    private String instrumentationKey;
    private String cloudRoleName;
}
