package com.tracebuddy.model;

import lombok.Data;
import lombok.Builder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * @author kiransahoo
 */
@Data
@Builder
public class ErrorMethodInfo {
    private String methodName;
    private int errorCount;
    private Map<String, Integer> errorTypes;
    private List<String> sampleMessages;
    private double avgDuration;
    private LocalDateTime lastErrorTime;
}
