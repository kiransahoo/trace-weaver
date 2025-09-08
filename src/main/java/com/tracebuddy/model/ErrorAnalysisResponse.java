package com.tracebuddy.model;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorAnalysisResponse {
    private Map<String, ErrorMethodInfo> errorMethods;
    private List<TraceSpan> errorTraces;
    private Map<String, Object> statistics;
    private String analysis;
}
