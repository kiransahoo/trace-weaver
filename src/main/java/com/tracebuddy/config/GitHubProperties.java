package com.tracebuddy.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    }

    // Convert list to map for easier lookup
    private Map<String, Map<String, String>> packageMappingsMap;

    @PostConstruct
    public void init() {
        // Convert list to map
        packageMappingsMap = new HashMap<>();

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

            packageMappingsMap.put(mapping.getPackageName(), config);
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
}
