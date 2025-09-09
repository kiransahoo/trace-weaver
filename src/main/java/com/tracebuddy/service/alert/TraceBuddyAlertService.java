package com.tracebuddy.service.alert;

import com.tracebuddy.config.GitHubProperties;
import com.tracebuddy.model.*;
import com.tracebuddy.service.hotspot.HotspotDetectionService;
import com.tracebuddy.service.llm.LLMAnalysisService;
import com.tracebuddy.engine.TraceMonitorEngine;
import com.tracebuddy.factory.TraceMonitorEngineFactory;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "alerts.enabled", havingValue = "true", matchIfMissing = false)
public class TraceBuddyAlertService {

    private final TraceMonitorEngineFactory engineFactory;
    private final HotspotDetectionService hotspotDetectionService;
    private final LLMAnalysisService llmAnalysisService;
    private final GitHubProperties gitHubProperties;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${alerts.email.from:alerts@tracebuddy.com}")
    private String fromEmail;

    @Value("${alerts.cooldown.minutes:30}")
    private int cooldownMinutes;

    private final Map<String, LocalDateTime> alertHistory = new ConcurrentHashMap<>();

    @Scheduled(cron = "${alerts.schedule.check:0 */15 * * * *}")
    public void checkAllPackages() {
        log.info("Starting scheduled SLA checks");

        for (GitHubProperties.PackageMapping mapping : gitHubProperties.getAlertEnabledMappings()) {
            try {
                checkPackageSLA(mapping);
            } catch (Exception e) {
                log.error("Error checking package {}: {}", mapping.getPackageName(), e.getMessage());
            }
        }
    }

    public void checkPackageSLA(GitHubProperties.PackageMapping mapping) {
        log.info("Checking SLA for: {} (TimeRange: {})",
                mapping.getPackageName(), mapping.getAlerts().getTimeRange());

        try {
            TraceMonitorEngine engine = engineFactory.getEngine();

            // Query traces using configured time range
            List<TraceSpan> traces = engine.queryTracesWithAllFilters(
                    mapping.getAlerts().getDurationThresholdMs(),
                    mapping.getAlerts().getTimeRange(),
                    null,  // resourceGroup
                    null,  // instrumentationKey
                    mapping.getCloudRoleName(),
                    null,  // className
                    mapping.getPackageName(),
                    true   // includeSubPackages
            );

            if (traces.isEmpty()) {
                log.debug("No traces found for {}", mapping.getPackageName());
                return;
            }

            // Calculate statistics
            Map<String, Object> statistics = engine.calculateStatisticsFromTraces(traces);

            // Detect hotspots
            List<PerformanceHotspot> hotspots = hotspotDetectionService.detectHotspots(traces);

            // Get AI analysis for each hotspot and store separately
            Map<String, String> aiAnalysisMap = new HashMap<>();
            for (PerformanceHotspot hotspot : hotspots.stream().limit(5).collect(Collectors.toList())) {
                try {
                    // Find slowest trace for this operation
                    TraceSpan slowestTrace = traces.stream()
                            .filter(t -> t.getOperationName().equals(hotspot.getOperation()))
                            .max(Comparator.comparing(TraceSpan::getDurationMs))
                            .orElse(null);

                    // Get all traces for this operation
                    List<TraceSpan> operationTraces = traces.stream()
                            .filter(t -> t.getOperationName().equals(hotspot.getOperation()))
                            .collect(Collectors.toList());

                    // Call LLM analysis
                    String analysis = llmAnalysisService.analyzeMethodPerformance(
                            hotspot.getOperation(),
                            slowestTrace,
                            operationTraces
                    );

                    aiAnalysisMap.put(hotspot.getOperation(), analysis);

                    // Extract recommendations from AI analysis and set them
                    List<String> recommendations = extractRecommendations(analysis);
                    hotspot.setRecommendations(recommendations);

                } catch (Exception e) {
                    log.error("Failed to get AI analysis for {}", hotspot.getOperation(), e);
                    // Set default recommendations
                    hotspot.setRecommendations(getDefaultRecommendations(hotspot));
                }
            }

            // Check SLA violations
            List<SLAViolation> violations = checkViolations(statistics, hotspots, mapping.getAlerts().getSla());

            // Determine if we should alert
            if (!violations.isEmpty() && shouldAlert(mapping.getPackageName())) {
                sendAlert(mapping, hotspots, aiAnalysisMap, statistics, violations);
            }

        } catch (Exception e) {
            log.error("Error in SLA check for {}: {}", mapping.getPackageName(), e.getMessage());
        }
    }

    private List<SLAViolation> checkViolations(Map<String, Object> stats,
                                               List<PerformanceHotspot> hotspots,
                                               GitHubProperties.SLAConfig sla) {
        List<SLAViolation> violations = new ArrayList<>();

        // Check average duration
        Double avgDuration = (Double) stats.get("avgDuration");
        if (avgDuration != null && avgDuration > sla.getCriticalDurationMs()) {
            violations.add(SLAViolation.builder()
                    .type("RESPONSE_TIME")
                    .severity("CRITICAL")
                    .actualValue(avgDuration)
                    .threshold(sla.getCriticalDurationMs().doubleValue())
                    .message(String.format("Avg response time %.2fms exceeds %dms",
                            avgDuration, sla.getCriticalDurationMs()))
                    .build());
        }

        // Check P95
        Double p95 = (Double) stats.get("percentile95");
        if (p95 != null && p95 > sla.getPercentileThresholdMs()) {
            violations.add(SLAViolation.builder()
                    .type("PERCENTILE")
                    .severity("HIGH")
                    .actualValue(p95)
                    .threshold(sla.getPercentileThresholdMs().doubleValue())
                    .message(String.format("P95 %.2fms exceeds %dms",
                            p95, sla.getPercentileThresholdMs()))
                    .build());
        }

        // Check error rate
        Long total = (Long) stats.get("totalCount");
        Long errors = (Long) stats.get("errorCount");
        if (total != null && errors != null && total > sla.getMinSampleSize()) {
            double errorRate = (double) errors / total;
            if (errorRate > sla.getCriticalErrorRate()) {
                violations.add(SLAViolation.builder()
                        .type("ERROR_RATE")
                        .severity("CRITICAL")
                        .actualValue(errorRate * 100)
                        .threshold(sla.getCriticalErrorRate() * 100)
                        .message(String.format("Error rate %.2f%% exceeds %.2f%%",
                                errorRate * 100, sla.getCriticalErrorRate() * 100))
                        .build());
            }
        }

        // Check individual hotspot durations
        for (PerformanceHotspot hotspot : hotspots) {
            if (hotspot.getAvgDurationMs() > sla.getCriticalDurationMs()) {
                hotspot.setSeverity("CRITICAL");
            } else if (hotspot.getAvgDurationMs() > sla.getHighDurationMs()) {
                hotspot.setSeverity("HIGH");
            } else {
                hotspot.setSeverity("MEDIUM");
            }
        }

        return violations;
    }

    private boolean shouldAlert(String packageName) {
        String key = "alert_" + packageName;
        LocalDateTime lastAlert = alertHistory.get(key);

        if (lastAlert == null || lastAlert.isBefore(LocalDateTime.now().minusMinutes(cooldownMinutes))) {
            alertHistory.put(key, LocalDateTime.now());
            return true;
        }
        return false;
    }

    private void sendAlert(GitHubProperties.PackageMapping mapping,
                           List<PerformanceHotspot> hotspots,
                           Map<String, String> aiAnalysisMap,
                           Map<String, Object> statistics,
                           List<SLAViolation> violations) {

        // Determine recipients
        List<String> recipients = new ArrayList<>();
        if (violations.stream().anyMatch(v -> "CRITICAL".equals(v.getSeverity()))) {
            recipients.addAll(mapping.getAlerts().getRecipients().getCritical());
            recipients.addAll(mapping.getAlerts().getRecipients().getSlaBreachRecipients());
        }
        recipients.addAll(mapping.getAlerts().getRecipients().getDefaultRecipients());

        recipients = recipients.stream()
                .filter(r -> r != null && !r.trim().isEmpty())
                .distinct()
                .collect(Collectors.toList());

        if (recipients.isEmpty() || mailSender == null) {
            log.warn("No recipients configured or mail sender not available");
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

            helper.setFrom(fromEmail, "TraceBuddy SLA Monitor");
            helper.setTo(recipients.toArray(new String[0]));

            String severity = violations.stream()
                    .anyMatch(v -> "CRITICAL".equals(v.getSeverity())) ? "CRITICAL" : "HIGH";

            helper.setSubject(String.format("🔴 [%s] SLA Alert - %s - %d violations",
                    severity,
                    mapping.getProject() != null ? mapping.getProject() : mapping.getPackageName(),
                    violations.size()));

            String html = buildEmailHtml(mapping, hotspots, aiAnalysisMap, statistics, violations);
            helper.setText(html, true);

            mailSender.send(message);
            log.info("Alert email sent to {} recipients", recipients.size());

        } catch (Exception e) {
            log.error("Failed to send email alert", e);
        }
    }

    private String buildEmailHtml(GitHubProperties.PackageMapping mapping,
                                  List<PerformanceHotspot> hotspots,
                                  Map<String, String> aiAnalysisMap,
                                  Map<String, Object> statistics,
                                  List<SLAViolation> violations) {

        StringBuilder html = new StringBuilder();

        // HTML header and styles
        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n");
        html.append("<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<style>\n");
        html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif; color: #333; margin: 0; padding: 0; }\n");
        html.append(".container { max-width: 800px; margin: 0 auto; }\n");
        html.append(".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; }\n");
        html.append(".header h1 { margin: 0 0 10px 0; font-size: 28px; }\n");
        html.append(".header p { margin: 5px 0; opacity: 0.9; }\n");
        html.append(".content { padding: 30px; background: #f8f9fa; }\n");
        html.append(".section { background: white; border-radius: 8px; padding: 20px; margin-bottom: 20px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n");
        html.append(".violation { background: #fff5f5; border-left: 4px solid #e53e3e; padding: 15px; margin: 10px 0; border-radius: 4px; }\n");
        html.append(".violation strong { color: #c53030; }\n");
        html.append(".hotspot { background: white; border: 1px solid #e2e8f0; padding: 15px; margin: 15px 0; border-radius: 8px; }\n");
        html.append(".hotspot h3 { margin-top: 0; color: #2d3748; }\n");
        html.append(".recommendation { background: #f0fdf4; border-left: 3px solid #48bb78; padding: 10px; margin: 8px 0; border-radius: 4px; }\n");
        html.append("table { width: 100%; border-collapse: collapse; }\n");
        html.append("th { background: #f7fafc; padding: 12px; text-align: left; font-weight: 600; border-bottom: 2px solid #e2e8f0; }\n");
        html.append("td { padding: 12px; border-bottom: 1px solid #e2e8f0; }\n");
        html.append(".status-ok { color: #48bb78; font-weight: 600; }\n");
        html.append(".status-warning { color: #ed8936; font-weight: 600; }\n");
        html.append(".status-critical { color: #e53e3e; font-weight: 600; }\n");
        html.append(".footer { background: #2d3748; color: white; padding: 20px; text-align: center; }\n");
        html.append(".footer a { color: #90cdf4; text-decoration: none; }\n");
        html.append("</style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("<div class=\"container\">\n");

        // Header section
        html.append("<div class=\"header\">\n");
        html.append("<h1>⚠️ SLA Violation Alert</h1>\n");
        html.append("<p><strong>Package:</strong> ").append(mapping.getPackageName()).append("</p>\n");
        html.append("<p><strong>Time Range:</strong> ").append(mapping.getAlerts().getTimeRange()).append("</p>\n");
        html.append("<p><strong>Generated:</strong> ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("</p>\n");
        if (mapping.getCloudRoleName() != null) {
            html.append("<p><strong>Service:</strong> ").append(mapping.getCloudRoleName()).append("</p>\n");
        }
        html.append("</div>\n");

        html.append("<div class=\"content\">\n");

        // SLA Violations Section
        html.append("<div class=\"section\">\n");
        html.append("<h2>🚨 SLA Violations Detected</h2>\n");
        for (SLAViolation violation : violations) {
            html.append("<div class=\"violation\">\n");
            html.append("<strong>").append(violation.getType()).append("</strong> ");
            html.append("<span style=\"background: #fed7d7; color: #c53030; padding: 2px 8px; border-radius: 4px; font-size: 12px;\">")
                    .append(violation.getSeverity()).append("</span><br>\n");
            html.append("<div style=\"margin-top: 8px;\">").append(violation.getMessage()).append("</div>\n");
            html.append("<div style=\"margin-top: 5px; font-size: 14px; color: #718096;\">");
            html.append("Actual: <strong>").append(String.format("%.2f", violation.getActualValue())).append("</strong> | ");
            html.append("Threshold: <strong>").append(String.format("%.2f", violation.getThreshold())).append("</strong>");
            html.append("</div>\n");
            html.append("</div>\n");
        }
        html.append("</div>\n");

        // Current Metrics Section
        html.append("<div class=\"section\">\n");
        html.append("<h2>📊 Current Performance Metrics</h2>\n");
        html.append("<table>\n");
        html.append("<thead>\n");
        html.append("<tr><th>Metric</th><th>Value</th><th>Status</th></tr>\n");
        html.append("</thead>\n");
        html.append("<tbody>\n");

        // Average Duration
        Double avgDuration = (Double) statistics.get("avgDuration");
        html.append("<tr>\n");
        html.append("<td>Average Duration</td>\n");
        html.append("<td>").append(String.format("%.2f ms", avgDuration)).append("</td>\n");
        html.append("<td class=\"");
        if (avgDuration > mapping.getAlerts().getSla().getCriticalDurationMs()) {
            html.append("status-critical\">❌ CRITICAL");
        } else if (avgDuration > mapping.getAlerts().getSla().getHighDurationMs()) {
            html.append("status-warning\">⚠️ WARNING");
        } else {
            html.append("status-ok\">✅ OK");
        }
        html.append("</td>\n</tr>\n");

        // P95 Duration
        Double p95 = (Double) statistics.get("percentile95");
        html.append("<tr>\n");
        html.append("<td>P95 Duration</td>\n");
        html.append("<td>").append(String.format("%.2f ms", p95)).append("</td>\n");
        html.append("<td class=\"");
        if (p95 > mapping.getAlerts().getSla().getPercentileThresholdMs()) {
            html.append("status-critical\">❌ EXCEEDED");
        } else {
            html.append("status-ok\">✅ OK");
        }
        html.append("</td>\n</tr>\n");

        // Error Rate
        Long errorCount = (Long) statistics.get("errorCount");
        Long totalCount = (Long) statistics.get("totalCount");
        double errorRate = totalCount > 0 ? (double) errorCount / totalCount * 100 : 0;
        html.append("<tr>\n");
        html.append("<td>Error Rate</td>\n");
        html.append("<td>").append(String.format("%.2f%%", errorRate)).append("</td>\n");
        html.append("<td class=\"");
        if (errorRate > mapping.getAlerts().getSla().getCriticalErrorRate() * 100) {
            html.append("status-critical\">❌ HIGH");
        } else {
            html.append("status-ok\">✅ OK");
        }
        html.append("</td>\n</tr>\n");

        // Total Requests
        html.append("<tr>\n");
        html.append("<td>Total Requests</td>\n");
        html.append("<td>").append(totalCount).append("</td>\n");
        html.append("<td>-</td>\n");
        html.append("</tr>\n");

        // P99 if available
        Double p99 = (Double) statistics.get("percentile99");
        if (p99 != null) {
            html.append("<tr>\n");
            html.append("<td>P99 Duration</td>\n");
            html.append("<td>").append(String.format("%.2f ms", p99)).append("</td>\n");
            html.append("<td>-</td>\n");
            html.append("</tr>\n");
        }

        html.append("</tbody>\n");
        html.append("</table>\n");
        html.append("</div>\n");

        // Top Performance Hotspots
        html.append("<div class=\"section\">\n");
        html.append("<h2>🔥 Top Performance Hotspots</h2>\n");

        int count = 0;
        for (PerformanceHotspot hotspot : hotspots) {
            if (++count > 5) break;

            html.append("<div class=\"hotspot\">\n");
            html.append("<h3>").append(hotspot.getOperation()).append("</h3>\n");

            // Severity badge
            String severityColor = "CRITICAL".equals(hotspot.getSeverity()) ? "#e53e3e" :
                    "HIGH".equals(hotspot.getSeverity()) ? "#ed8936" : "#3182ce";
            html.append("<span style=\"background: ").append(severityColor).append("; color: white; padding: 4px 12px; border-radius: 4px; font-size: 12px;\">")
                    .append(hotspot.getSeverity() != null ? hotspot.getSeverity() : "MEDIUM")
                    .append("</span>\n");

            // Metrics table
            html.append("<table style=\"margin-top: 15px;\">\n");
            html.append("<tr><td style=\"width: 150px;\"><strong>Avg Duration:</strong></td><td>")
                    .append(String.format("%.2f ms", hotspot.getAvgDurationMs())).append("</td></tr>\n");
            html.append("<tr><td><strong>Max Duration:</strong></td><td>")
                    .append(String.format("%.2f ms", hotspot.getMaxDurationMs())).append("</td></tr>\n");
            html.append("<tr><td><strong>Occurrences:</strong></td><td>")
                    .append(hotspot.getOccurrenceCount()).append("</td></tr>\n");
            html.append("<tr><td><strong>Error Rate:</strong></td><td>")
                    .append(String.format("%.2f%%", hotspot.getErrorRate() * 100)).append("</td></tr>\n");
            html.append("</table>\n");

            // AI Recommendations
            if (hotspot.getRecommendations() != null && !hotspot.getRecommendations().isEmpty()) {
                html.append("<div style=\"margin-top: 15px;\">\n");
                html.append("<h4 style=\"margin-bottom: 10px;\">🤖 AI Recommendations:</h4>\n");
                for (String rec : hotspot.getRecommendations()) {
                    html.append("<div class=\"recommendation\">• ").append(rec).append("</div>\n");
                }
                html.append("</div>\n");
            }

            html.append("</div>\n");
        }
        html.append("</div>\n");

        html.append("</div>\n"); // close content

        // Footer
        html.append("<div class=\"footer\">\n");
        if (mapping.getRepo() != null) {
            html.append("<p>Repository: ");
            if (mapping.getOwner() != null) {
                html.append(mapping.getOwner()).append("/");
            }
            html.append(mapping.getRepo());
            html.append(" (").append(mapping.getBranch()).append(")</p>\n");
        }
        html.append("<p style=\"margin-top: 10px; font-size: 14px; opacity: 0.8;\">Generated by TraceBuddy Performance Monitor</p>\n");
        html.append("</div>\n");

        html.append("</div>\n"); // close container
        html.append("</body>\n");
        html.append("</html>");

        return html.toString();
    }


    private List<String> extractRecommendations(String analysis) {
        List<String> recommendations = new ArrayList<>();

        if (analysis == null || analysis.isEmpty()) {
            return recommendations;
        }

        String[] lines = analysis.split("\n");
        for (String line : lines) {
            line = line.trim();
            // Look for numbered items, bullet points, or lines with action words
            if (line.matches("^\\d+\\..*") ||
                    line.startsWith("-") ||
                    line.startsWith("•") ||
                    line.toLowerCase().contains("consider") ||
                    line.toLowerCase().contains("implement") ||
                    line.toLowerCase().contains("optimize") ||
                    line.toLowerCase().contains("add") ||
                    line.toLowerCase().contains("use")) {

                String recommendation = line.replaceFirst("^\\d+\\.|^-|^•", "").trim();
                if (recommendation.length() > 10) {
                    recommendations.add(recommendation);
                    if (recommendations.size() >= 3) break; // Limit to 3 recommendations
                }
            }
        }

        return recommendations;
    }

    private List<String> getDefaultRecommendations(PerformanceHotspot hotspot) {
        List<String> recommendations = new ArrayList<>();

        if (hotspot.getAvgDurationMs() > 5000) {
            recommendations.add("Consider implementing caching to reduce response time");
            recommendations.add("Review and optimize database queries in this method");
        } else if (hotspot.getAvgDurationMs() > 2000) {
            recommendations.add("Analyze method for optimization opportunities");
            recommendations.add("Consider adding database indexes if applicable");
        }

        if (hotspot.getErrorRate() > 0.1) {
            recommendations.add("Implement retry logic with exponential backoff");
            recommendations.add("Add circuit breaker pattern to prevent cascading failures");
        }

        if (hotspot.getOccurrenceCount() > 100) {
            recommendations.add("This is a hot path - prioritize optimization efforts");
        }

        if (recommendations.isEmpty()) {
            recommendations.add("Review method implementation for performance improvements");
            recommendations.add("Consider profiling to identify bottlenecks");
        }

        return recommendations;
    }

    @Data
    @Builder
    public static class SLAViolation {
        private String type;
        private String severity;
        private Double actualValue;
        private Double threshold;
        private String message;
    }
}