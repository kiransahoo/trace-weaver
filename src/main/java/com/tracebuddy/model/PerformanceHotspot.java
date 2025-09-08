package com.tracebuddy.model;

import lombok.Data;
import lombok.Builder;
import java.util.List;

@Data
@Builder
public class PerformanceHotspot {
    private String operation;
    private double avgDurationMs;
    private double maxDurationMs;
    private int occurrenceCount;
    private double errorRate;
    private List<String> relatedOperations;
    private List<String> recommendations;
    private String severity;
}
