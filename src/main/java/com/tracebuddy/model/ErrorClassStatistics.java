package com.tracebuddy.model;

import lombok.Data;
import lombok.Builder;
import java.util.Map;

/**
 * @author kiransahoo
 */
@Data
@Builder
public class ErrorClassStatistics {
    private String className;
    private String packageName;
    private int totalErrors;
    private int uniqueMethodsWithErrors;
    private Map<String, Integer> errorTypes;
    private double avgDuration;
}
