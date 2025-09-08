package com.tracebuddy.model;

import lombok.Data;
import lombok.Builder;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class TraceSpan {
    private String operationName;
    private double durationMs;
    private LocalDateTime timestamp;
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private Map<String, Object> attributes;
    private String status;
    private String cloudRoleName;
    private String cloudRoleInstance;
}
