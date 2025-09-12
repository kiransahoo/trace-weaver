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

            // 1. Get aggregated metrics
            Map<String, Object> metrics = engine.queryPackageMetricsForAlerts(
                    mapping.getCloudRoleName(),
                    mapping.getPackageName(),
                    mapping.getAlerts().getTimeRange(),
                    mapping.getAlerts().getDurationThresholdMs(),
                    mapping.getAlerts().getSla()
            );

            log.info("Metrics received: {}", metrics);

            if (metrics.isEmpty() || (Long) metrics.get("TotalCount") == 0) {
                log.debug("No data found for {}", mapping.getPackageName());
                return;
            }

            // 2. Check violations
            List<SLAViolation> violations = checkViolationsFromMetrics(metrics, mapping.getAlerts().getSla());
            log.info("Found {} violations", violations.size());

            // 3. Always get top operations for context (even without violations)
            List<PerformanceHotspot> topHotspots = engine.queryTopSlowOperations(
                    mapping.getCloudRoleName(),
                    mapping.getPackageName(),
                    mapping.getAlerts().getTimeRange(),
                    mapping.getAlerts().getSla(),
                    5  // Get top 5
            );

            log.info("Found {} top operations", topHotspots.size());

            // 4. Get AI analysis for top operations
            Map<String, String> aiAnalysisMap = new HashMap<>();
            for (PerformanceHotspot hotspot : topHotspots) {
                try {
                    log.info("Getting sample trace for: {}", hotspot.getOperation());

                    TraceSpan sampleTrace = engine.getSampleTraceForOperation(
                            hotspot.getOperation(),
                            mapping.getCloudRoleName(),
                            mapping.getAlerts().getTimeRange()
                    );

                    if (sampleTrace != null) {
                        log.info("Analyzing with LLM...");
                        String analysis = llmAnalysisService.analyzeMethodPerformance(
                                hotspot.getOperation(),
                                sampleTrace,
                                Collections.singletonList(sampleTrace)
                        );
                        aiAnalysisMap.put(hotspot.getOperation(), analysis);
                        hotspot.setRecommendations(extractRecommendations(analysis));
                    } else {
                        log.warn("No sample trace found for {}", hotspot.getOperation());
                        String defaultAnalysis = generateDefaultAnalysis(hotspot);
                        aiAnalysisMap.put(hotspot.getOperation(), defaultAnalysis);
                        hotspot.setRecommendations(getDefaultRecommendations(hotspot));
                    }
                } catch (Exception e) {
                    log.error("Failed to analyze {}: {}", hotspot.getOperation(), e.getMessage());
                    aiAnalysisMap.put(hotspot.getOperation(), "Analysis unavailable");
                    hotspot.setRecommendations(getDefaultRecommendations(hotspot));
                }
            }

            // 5. Send alert only if violations exist and not in cooldown
            if (!violations.isEmpty() && shouldAlert(mapping.getPackageName())) {
                log.info("Sending alert email...");
                sendAlert(mapping, topHotspots, aiAnalysisMap, metrics, violations);
            } else if (violations.isEmpty()) {
                log.info("No violations detected, skipping alert");
            } else {
                log.info("Alert in cooldown period");
            }

        } catch (Exception e) {
            log.error("Error in checkPackageSLA", e);
            e.printStackTrace();
        }
    }

    // Add this helper method
    private String generateDefaultAnalysis(PerformanceHotspot hotspot) {
        StringBuilder analysis = new StringBuilder();

        analysis.append("Performance Profile:\n\n");

        if (hotspot.getAvgDurationMs() > 2000) {
            analysis.append("⚠️ This method is experiencing significant latency issues.\n\n");
            analysis.append("Potential root causes:\n");
            analysis.append("• Inefficient database queries or missing indexes\n");
            analysis.append("• Synchronous external service calls\n");
            analysis.append("• Large data processing operations\n");
            analysis.append("• Resource contention or locking issues\n\n");
            analysis.append("Immediate actions:\n");
            analysis.append("• Add performance profiling to identify bottlenecks\n");
            analysis.append("• Review database query execution plans\n");
            analysis.append("• Consider implementing caching strategies\n");
        } else if (hotspot.getAvgDurationMs() > 1000) {
            analysis.append("This method shows moderate performance degradation.\n\n");
            analysis.append("Optimization opportunities:\n");
            analysis.append("• Implement result caching\n");
            analysis.append("• Optimize data access patterns\n");
            analysis.append("• Consider async processing where applicable\n");
        }

        if (hotspot.getErrorRate() > 0.05) {
            analysis.append("\nReliability concerns detected (").append(String.format("%.1f%% error rate", hotspot.getErrorRate() * 100)).append("):\n");
            analysis.append("• Implement retry logic with exponential backoff\n");
            analysis.append("• Add circuit breaker pattern\n");
            analysis.append("• Improve error handling and logging\n");
        }

        return analysis.toString();
    }

    // ADD this new method
    private List<SLAViolation> checkViolationsFromMetrics(
            Map<String, Object> metrics,
            GitHubProperties.SLAConfig sla) {

        List<SLAViolation> violations = new ArrayList<>();

        // Check average duration
        Double avgDuration = (Double) metrics.get("AvgDuration");
        if (avgDuration != null && avgDuration > sla.getCriticalDurationMs()) {
            violations.add(SLAViolation.builder()
                    .type("AVG_RESPONSE_TIME")
                    .severity("CRITICAL")
                    .actualValue(avgDuration)
                    .threshold(sla.getCriticalDurationMs().doubleValue())
                    .message(String.format("Avg response time %.2fms exceeds critical threshold %dms",
                            avgDuration, sla.getCriticalDurationMs()))
                    .build());
        }

        // Check configured percentile
        String percentileKey = "percentile" + sla.getPercentile();
        Double percentileValue = (Double) metrics.get(percentileKey);
        if (percentileValue != null && percentileValue > sla.getPercentileThresholdMs()) {
            violations.add(SLAViolation.builder()
                    .type("P" + sla.getPercentile())
                    .severity("HIGH")
                    .actualValue(percentileValue)
                    .threshold(sla.getPercentileThresholdMs().doubleValue())
                    .message(String.format("P%d %.2fms exceeds threshold %dms",
                            sla.getPercentile(), percentileValue, sla.getPercentileThresholdMs()))
                    .build());
        }

        // Check error rate
        Long total = (Long) metrics.get("TotalCount");
        Long errors = (Long) metrics.get("ErrorCount");
        if (total != null && errors != null && total > sla.getMinSampleSize()) {
            double errorRate = (double) errors / total;

            if (errorRate > sla.getCriticalErrorRate()) {
                violations.add(SLAViolation.builder()
                        .type("ERROR_RATE")
                        .severity("CRITICAL")
                        .actualValue(errorRate * 100)
                        .threshold(sla.getCriticalErrorRate() * 100)
                        .message(String.format("Error rate %.2f%% exceeds critical threshold %.2f%%",
                                errorRate * 100, sla.getCriticalErrorRate() * 100))
                        .build());
            }
        }

        return violations;
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
        html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif; color: #333; margin: 0; padding: 0; background: #f5f5f5; }\n");
        html.append(".container { max-width: 900px; margin: 0 auto; background: white; }\n");
        html.append(".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; }\n");
        html.append(".header h1 { margin: 0 0 10px 0; font-size: 24px; display: flex; align-items: center; }\n");
        html.append(".header p { margin: 5px 0; opacity: 0.95; font-size: 14px; }\n");
        html.append(".content { padding: 30px; }\n");
        html.append(".section { background: white; border-radius: 8px; padding: 20px; margin-bottom: 20px; border: 1px solid #e2e8f0; }\n");
        html.append(".violation { background: #fff5f5; border-left: 4px solid #e53e3e; padding: 15px; margin: 10px 0; border-radius: 4px; }\n");
        html.append(".violation-header { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }\n");
        html.append(".severity-badge { padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; text-transform: uppercase; }\n");
        html.append(".critical { background: #fed7d7; color: #c53030; }\n");
        html.append(".high { background: #fed7e2; color: #97266d; }\n");
        html.append(".medium { background: #feebc8; color: #c05621; }\n");

        // Hotspot styles to match UI
        html.append(".hotspot { background: #f8f9fa; border: 1px solid #dee2e6; border-radius: 8px; margin-bottom: 20px; overflow: hidden; }\n");
        html.append(".hotspot-header { background: white; padding: 15px 20px; border-bottom: 1px solid #dee2e6; display: flex; align-items: center; justify-content: space-between; }\n");
        html.append(".hotspot-title { font-size: 14px; color: #212529; font-family: 'SF Mono', Monaco, monospace; word-break: break-all; margin: 0; }\n");
        html.append(".hotspot-badge { padding: 4px 12px; border-radius: 4px; font-size: 11px; font-weight: 600; text-transform: uppercase; }\n");
        html.append(".hotspot-metrics { display: flex; padding: 15px 20px; gap: 30px; background: white; }\n");
        html.append(".metric-item { flex: 1; }\n");
        html.append(".metric-label { font-size: 12px; color: #6c757d; margin-bottom: 4px; }\n");
        html.append(".metric-value { font-size: 16px; color: #212529; font-weight: 600; }\n");
        html.append(".ai-analysis { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 20px; margin: 15px; border-radius: 8px; }\n");
        html.append(".ai-analysis h4 { margin: 0 0 12px 0; font-size: 14px; display: flex; align-items: center; gap: 8px; }\n");
        html.append(".ai-analysis-content { font-size: 13px; line-height: 1.6; }\n");
        html.append(".recommendations { background: #f0fdf4; border-left: 4px solid #48bb78; padding: 15px; margin: 15px; border-radius: 4px; }\n");
        html.append(".recommendations h4 { margin: 0 0 10px 0; color: #22543d; font-size: 14px; }\n");
        html.append(".recommendation-item { color: #2f855a; margin: 8px 0; padding-left: 20px; position: relative; font-size: 13px; }\n");
        html.append(".recommendation-item:before { content: '→'; position: absolute; left: 0; }\n");

        // Table styles
        html.append("table { width: 100%; border-collapse: collapse; }\n");
        html.append("th { background: #f7fafc; padding: 12px; text-align: left; font-weight: 600; font-size: 13px; color: #4a5568; }\n");
        html.append("td { padding: 12px; border-top: 1px solid #e2e8f0; font-size: 14px; }\n");
        html.append(".status-ok { color: #48bb78; font-weight: 600; }\n");
        html.append(".status-warning { color: #ed8936; font-weight: 600; }\n");
        html.append(".status-critical { color: #e53e3e; font-weight: 600; }\n");
        html.append(".footer { background: #2d3748; color: white; padding: 20px; text-align: center; font-size: 12px; }\n");
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
        html.append("<h2 style=\"margin-top: 0; font-size: 18px;\">🚨 SLA Violations Detected</h2>\n");
        for (SLAViolation violation : violations) {
            html.append("<div class=\"violation\">\n");
            html.append("<div class=\"violation-header\">\n");
            html.append("<strong>").append(violation.getType()).append("</strong>\n");
            html.append("<span class=\"severity-badge ").append(violation.getSeverity().toLowerCase()).append("\">")
                    .append(violation.getSeverity()).append("</span>\n");
            html.append("</div>\n");
            html.append("<div>").append(violation.getMessage()).append("</div>\n");
            html.append("<div style=\"margin-top: 8px; font-size: 13px; color: #718096;\">");
            html.append("Actual: <strong>").append(String.format("%.2f", violation.getActualValue())).append("</strong> | ");
            html.append("Threshold: <strong>").append(String.format("%.2f", violation.getThreshold())).append("</strong>");
            html.append("</div>\n");
            html.append("</div>\n");
        }
        html.append("</div>\n");

        // Current Metrics Section
        html.append("<div class=\"section\">\n");
        html.append("<h2 style=\"margin-top: 0; font-size: 18px;\">📊 Current Performance Metrics</h2>\n");
        html.append("<table>\n");
        html.append("<thead>\n");
        html.append("<tr><th>Metric</th><th>Value</th><th>Status</th></tr>\n");
        html.append("</thead>\n");
        html.append("<tbody>\n");

        // Average Duration
        Double avgDuration = (Double) statistics.get("AvgDuration");
        if (avgDuration == null) avgDuration = (Double) statistics.get("avgDuration");
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
        String p95Key = "percentile" + mapping.getAlerts().getSla().getPercentile();
        Double p95 = (Double) statistics.get(p95Key);
        if (p95 == null) p95 = (Double) statistics.get("percentile95");
        if (p95 != null) {
            html.append("<tr>\n");
            html.append("<td>P95 Duration</td>\n");
            html.append("<td>").append(String.format("%.2f ms", p95)).append("</td>\n");
            html.append("<td class=\"status-ok\">✅ OK</td>\n");
            html.append("</tr>\n");
        }

        // Error Rate
        Long errorCount = (Long) statistics.get("ErrorCount");
        if (errorCount == null) errorCount = (Long) statistics.get("errorCount");
        Long totalCount = (Long) statistics.get("TotalCount");
        if (totalCount == null) totalCount = (Long) statistics.get("totalCount");

        double errorRate = (totalCount != null && totalCount > 0) ?
                ((double) errorCount / totalCount * 100) : 0;
        html.append("<tr>\n");
        html.append("<td>Error Rate</td>\n");
        html.append("<td>").append(String.format("%.2f%%", errorRate)).append("</td>\n");
        html.append("<td class=\"status-ok\">✅ OK</td>\n");
        html.append("</tr>\n");

        html.append("<tr>\n");
        html.append("<td>Total Requests</td>\n");
        html.append("<td>").append(totalCount).append("</td>\n");
        html.append("<td>-</td>\n");
        html.append("</tr>\n");

        html.append("</tbody>\n");
        html.append("</table>\n");
        html.append("</div>\n");

        // Performance Hotspots Section - Matching UI exactly
        if (hotspots != null && !hotspots.isEmpty()) {
            html.append("<div class=\"section\">\n");
            html.append("<h2 style=\"margin-top: 0; font-size: 18px;\">🔥 Performance Hotspots</h2>\n");

            for (PerformanceHotspot hotspot : hotspots) {
                // Determine severity
                String severity = "MEDIUM";
                String badgeColor = "#ffc107";
                if (hotspot.getAvgDurationMs() > 2000) {
                    severity = "CRITICAL";
                    badgeColor = "#dc3545";
                } else if (hotspot.getAvgDurationMs() > 1000) {
                    severity = "HIGH";
                    badgeColor = "#fd7e14";
                }

                html.append("<div class=\"hotspot\">\n");

                // Header with method name and severity badge
                html.append("<div class=\"hotspot-header\">\n");
                html.append("<h3 class=\"hotspot-title\">").append(hotspot.getOperation()).append("</h3>\n");
                html.append("<span class=\"hotspot-badge\" style=\"background: ").append(badgeColor)
                        .append("; color: white;\">").append(severity).append("</span>\n");
                html.append("</div>\n");

                // Metrics row matching UI
                html.append("<div class=\"hotspot-metrics\">\n");

                html.append("<div class=\"metric-item\">\n");
                html.append("<div class=\"metric-label\">Avg</div>\n");
                html.append("<div class=\"metric-value\">").append(String.format("%.2fs", hotspot.getAvgDurationMs() / 1000.0)).append("</div>\n");
                html.append("</div>\n");

                html.append("<div class=\"metric-item\">\n");
                html.append("<div class=\"metric-label\">Max</div>\n");
                html.append("<div class=\"metric-value\">").append(String.format("%.2fs", hotspot.getMaxDurationMs() / 1000.0)).append("</div>\n");
                html.append("</div>\n");

                html.append("<div class=\"metric-item\">\n");
                html.append("<div class=\"metric-label\">Count</div>\n");
                html.append("<div class=\"metric-value\">").append(hotspot.getOccurrenceCount()).append("</div>\n");
                html.append("</div>\n");

                html.append("<div class=\"metric-item\">\n");
                html.append("<div class=\"metric-label\">Error Rate</div>\n");
                html.append("<div class=\"metric-value\">").append(String.format("%.1f%%", hotspot.getErrorRate() * 100)).append("</div>\n");
                html.append("</div>\n");

                html.append("</div>\n");

                // AI Analysis Section - Like "Analyze Code" button results
                String analysis = aiAnalysisMap.get(hotspot.getOperation());
                if (analysis != null && !analysis.isEmpty()) {
                    html.append("<div class=\"ai-analysis\">\n");
                    html.append("<h4>🤖 Code Analysis</h4>\n");
                    html.append("<div class=\"ai-analysis-content\">\n");

                    String[] lines = analysis.split("\n");
                    for (String line : lines) {
                        if (!line.trim().isEmpty()) {
                            html.append("<p style=\"margin: 8px 0;\">").append(line).append("</p>\n");
                        }
                    }

                    html.append("</div>\n");
                    html.append("</div>\n");
                }

                // Recommendations section
                if (hotspot.getRecommendations() != null && !hotspot.getRecommendations().isEmpty()) {
                    html.append("<div class=\"recommendations\">\n");
                    html.append("<h4>✅ Recommended Actions</h4>\n");
                    for (String rec : hotspot.getRecommendations()) {
                        html.append("<div class=\"recommendation-item\">").append(rec).append("</div>\n");
                    }
                    html.append("</div>\n");
                }

                html.append("</div>\n"); // end hotspot
            }

            html.append("</div>\n"); // end section
        }

        html.append("</div>\n"); // close content

        // Footer
        html.append("<div class=\"footer\">\n");
        html.append("<p>Generated by TraceBuddy Performance Monitor</p>\n");
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