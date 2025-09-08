package com.tracebuddy.service.llm;

import com.tracebuddy.model.TraceSpan;
import com.tracebuddy.model.PerformanceHotspot;
import com.tracebuddy.model.ErrorMethodInfo;
import java.util.List;
import java.util.Map;

/**
 * Interface for AI/LLM analysis engines
 * Supports OpenAI, Claude, Gemini, local models, etc.
 */
public interface AiAnalysisEngine {
    
    /**
     * Analyze traces and provide insights
     */
    String analyzeTraces(List<TraceSpan> traces, List<PerformanceHotspot> hotspots,
                        Map<String, Object> statistics);
    
    /**
     * Analyze method performance
     */
    String analyzeMethodPerformance(String methodName, TraceSpan slowTrace,
                                   List<TraceSpan> allTracesForMethod);
    
    /**
     * Answer user query about traces
     */
    String answerQuery(String userQuery, List<TraceSpan> traces,
                      List<PerformanceHotspot> hotspots);
    
    /**
     * Analyze errors
     */
    String analyzeErrors(String userQuery, Map<String, ErrorMethodInfo> errorMethods,
                        List<TraceSpan> errorTraces);
    
    /**
     * Analyze performance
     */
    String analyzePerformance(String userQuery, List<TraceSpan> traces,
                             Map<String, Object> performanceAnalysis);
    
    /**
     * Get engine type
     */
    String getEngineType();
}
