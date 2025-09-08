package com.tracebuddy.model;

import lombok.Data;
import lombok.Builder;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AnalysisResponse {
    private List<TraceSpan> traces;
    private List<PerformanceHotspot> hotspots;
    private Map<String, Object> statistics;
    private String llmAnalysis;
    private List<String> suggestions;
}
