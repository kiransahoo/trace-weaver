//package com.tracebuddy.config;
//
//import lombok.Data;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.boot.context.properties.ConfigurationProperties;
//import jakarta.annotation.PostConstruct;
//
//import java.util.ArrayList;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
//@Slf4j
//@Data
//@ConfigurationProperties(prefix = "github")
//public class GitHubProperties {
//
//    private String owner;
//    private String repo;
//    private String branch = "main";
//    private String token;
//    private String projectPath;
//
//    // Using List for better Spring Boot handling
//    private List<PackageMapping> mappings = new ArrayList<>();
//
//    @Data
//    public static class PackageMapping {
//        private String packageName;
//        private String provider;
//        private String owner;
//        private String organization;
//        private String project;
//        private String repo;
//        private String branch = "main";
//        private String token;
//        private String baseUrl;
//    }
//
//    // Convert list to map for easier lookup
//    private Map<String, Map<String, String>> packageMappingsMap;
//
//    @PostConstruct
//    public void init() {
//        // Convert list to map
//        packageMappingsMap = new HashMap<>();
//
//        for (PackageMapping mapping : mappings) {
//            Map<String, String> config = new HashMap<>();
//            config.put("provider", mapping.getProvider());
//            config.put("packageName", mapping.getPackageName());
//
//            if (mapping.getOwner() != null) config.put("owner", mapping.getOwner());
//            if (mapping.getOrganization() != null) config.put("organization", mapping.getOrganization());
//            if (mapping.getProject() != null) config.put("project", mapping.getProject());
//            if (mapping.getRepo() != null) config.put("repo", mapping.getRepo());
//            if (mapping.getBranch() != null) config.put("branch", mapping.getBranch());
//            if (mapping.getToken() != null) config.put("token", mapping.getToken());
//            if (mapping.getBaseUrl() != null) config.put("baseUrl", mapping.getBaseUrl());
//
//            packageMappingsMap.put(mapping.getPackageName(), config);
//        }
//
//        log.info("=== GitHubProperties Loaded ===");
//        log.info("Owner: {}", owner);
//        log.info("Repo: {}", repo);
//        log.info("Package mappings count: {}", packageMappingsMap.size());
//
//        packageMappingsMap.forEach((pkg, config) -> {
//            log.info("  {} -> {}", pkg, config.get("provider"));
//        });
//        log.info("==============================");
//    }
//
//    public Map<String, Map<String, String>> getPackageMappingsAsMap() {
//        return packageMappingsMap;
//    }
//}

package com.tracebuddy.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Data
@ConfigurationProperties(prefix = "github")
public class GitHubProperties {

    private String owner;
    private String repo;
    private String branch = "main";
    private String token;
    private String projectPath;

    // Using List for better Spring Boot handling
    private List<PackageMapping> mappings = new ArrayList<>();

    @Data
    public static class PackageMapping {
        private String packageName;
        private String provider;
        private String owner;
        private String organization;
        private String project;
        private String repo;
        private String branch = "main";
        private String token;
        private String baseUrl;

        // NEW FIELDS - Add these to your existing PackageMapping
        private String cloudRoleName;  // Maps to Azure Monitor AppRoleName
        private AlertConfig alerts = new AlertConfig();
    }

    // NEW INNER CLASSES - Add these
    @Data
    public static class AlertConfig {
        private boolean enabled = false;
        private String timeRange = "15m";  // Configurable time period
        private Long durationThresholdMs = 1000L;
        private Recipients recipients = new Recipients();
        private SLAConfig sla = new SLAConfig();
        private String slackChannel;
    }

    @Data
    public static class SLAConfig {
        private Long criticalDurationMs = 5000L;
        private Long highDurationMs = 2000L;
        private Double criticalErrorRate = 0.1;
        private Double highErrorRate = 0.05;
        private Integer percentile = 95;
        private Long percentileThresholdMs = 3000L;
        private Integer minSampleSize = 10;
    }

    @Data
    public static class Recipients {
        private List<String> defaultRecipients = new ArrayList<>();
        private List<String> critical = new ArrayList<>();
        private List<String> slaBreachRecipients = new ArrayList<>();
    }


    private Map<String, Map<String, String>> packageMappingsMap;


    private Map<String, PackageMapping> packageMappingsByName;

    @PostConstruct
    public void init() {
        // Your existing conversion logic
        packageMappingsMap = new HashMap<>();
        packageMappingsByName = new HashMap<>();  // NEW

        for (PackageMapping mapping : mappings) {
            Map<String, String> config = new HashMap<>();
            config.put("provider", mapping.getProvider());
            config.put("packageName", mapping.getPackageName());

            if (mapping.getOwner() != null) config.put("owner", mapping.getOwner());
            if (mapping.getOrganization() != null) config.put("organization", mapping.getOrganization());
            if (mapping.getProject() != null) config.put("project", mapping.getProject());
            if (mapping.getRepo() != null) config.put("repo", mapping.getRepo());
            if (mapping.getBranch() != null) config.put("branch", mapping.getBranch());
            if (mapping.getToken() != null) config.put("token", mapping.getToken());
            if (mapping.getBaseUrl() != null) config.put("baseUrl", mapping.getBaseUrl());

            // NEW
            if (mapping.getCloudRoleName() != null) config.put("cloudRoleName", mapping.getCloudRoleName());

            packageMappingsMap.put(mapping.getPackageName(), config);
            packageMappingsByName.put(mapping.getPackageName(), mapping);  // NEW
        }

        log.info("=== GitHubProperties Loaded ===");
        log.info("Owner: {}", owner);
        log.info("Repo: {}", repo);
        log.info("Package mappings count: {}", packageMappingsMap.size());

        packageMappingsMap.forEach((pkg, config) -> {
            log.info("  {} -> {}", pkg, config.get("provider"));
        });
        log.info("==============================");
    }

    public Map<String, Map<String, String>> getPackageMappingsAsMap() {
        return packageMappingsMap;
    }


    public PackageMapping getMappingByPackageName(String packageName) {
        return packageMappingsByName.get(packageName);
    }

    public List<PackageMapping> getAlertEnabledMappings() {
        return mappings.stream()
                .filter(m -> m.getAlerts() != null && m.getAlerts().isEnabled())
                .collect(Collectors.toList());
    }
}