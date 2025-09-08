package com.tracebuddy.integration.vcs;

import com.tracebuddy.config.GitHubProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.util.HashMap;
import java.util.Map;

/**
 * Main VCS Service that delegates to appropriate provider
 * @author kiransahoo
 */
@Slf4j
@Service
public class VcsService {

    @Autowired
    private GitHubProperties githubProperties;

    private Map<String, VcsProvider> providers = new HashMap<>();
    private Map<String, Map<String, String>> packageMappings;

    @Autowired(required = false)
    private GitHubVcsProvider githubProvider;

    @Autowired(required = false)
    private AzureDevOpsVcsProvider azureDevOpsProvider;

    @PostConstruct
    public void init() {
        log.info("Initializing VCS Service");
        
        // Register available providers
        if (githubProvider != null) {
            providers.put("github", githubProvider);
            log.info("Registered GitHub VCS provider");
        }
        
        if (azureDevOpsProvider != null) {
            providers.put("azure-devops", azureDevOpsProvider);
            log.info("Registered Azure DevOps VCS provider");
        }
        
        // Load package mappings
        this.packageMappings = githubProperties.getPackageMappingsAsMap();
        log.info("Loaded {} package mappings", packageMappings.size());
    }

    @Tool(description = "Find and read a Java file by class name from the appropriate repository")
    public String readJavaFileByClassName(String className) {
        log.info("Searching for class: {}", className);
        
        if (className == null || className.isEmpty()) {
            return "Error: Class name cannot be null or empty";
        }
        
        try {
            // Find the appropriate provider based on package mapping
            VcsProvider provider = findProviderForClass(className);
            
            if (provider == null) {
                log.error("No VCS provider found for class: {}", className);
                return "Error: No VCS provider configured for class: " + className;
            }
            
            return provider.readFileByClassName(className);
            
        } catch (Exception e) {
            log.error("Error searching for class file", e);
            return String.format("Error searching for class %s: %s", className, e.getMessage());
        }
    }

    @Tool(description = "Read a file from the repository by path")
    public String readFile(String path) {
        // Use default provider (GitHub) for direct file access
        VcsProvider provider = providers.get("github");
        if (provider != null) {
            return provider.readFile(path);
        }
        return "Error: No default VCS provider available";
    }

    @Tool(description = "List files in a repository directory")
    public String listFiles(String directory) {
        // Use default provider (GitHub) for directory listing
        VcsProvider provider = providers.get("github");
        if (provider != null) {
            return provider.listFiles(directory);
        }
        return "Error: No default VCS provider available";
    }

    @Tool(description = "Get repository information")
    public String getRepositoryInfo() {
        // Use default provider (GitHub) for repo info
        VcsProvider provider = providers.get("github");
        if (provider != null) {
            return provider.getRepositoryInfo();
        }
        return "Error: No default VCS provider available";
    }

    private VcsProvider findProviderForClass(String className) {
        // Normalize className first
        String normalizedClassName = normalizeClassName(className);
        
        // Extract package from normalized class name
        if (normalizedClassName.contains(".")) {
            String packageName = normalizedClassName.substring(0, normalizedClassName.lastIndexOf('.'));
            log.info("Extracted package name: '{}'", packageName);
            
            // Try exact match first, then progressively shorter prefixes
            String currentPackage = packageName;
            while (true) {
                if (packageMappings.containsKey(currentPackage)) {
                    Map<String, String> repoConfig = packageMappings.get(currentPackage);
                    String providerType = repoConfig.get("provider");
                    log.info("Found mapping for package '{}': provider={}", currentPackage, providerType);
                    
                    VcsProvider provider = providers.get(providerType);
                    if (provider != null) {
                        // Pass the configuration to the provider
                        if (provider instanceof GitHubVcsProvider) {
                            ((GitHubVcsProvider) provider).setRepoConfig(repoConfig);
                        } else if (provider instanceof AzureDevOpsVcsProvider) {
                            ((AzureDevOpsVcsProvider) provider).setRepoConfig(repoConfig);
                        }
                        return provider;
                    }
                }
                
                // Try shorter package name
                int dotIndex = currentPackage.lastIndexOf('.');
                if (dotIndex <= 0) {
                    break;
                }
                currentPackage = currentPackage.substring(0, dotIndex);
            }
        }
        
        // No mapping found, use default provider
        log.info("No mapping found for class '{}', using default provider", className);
        return providers.get("github");
    }

    private String normalizeClassName(String className) {
        String normalized = className;
        
        // Remove method name if present
        if (className.contains("(")) {
            normalized = className.substring(0, className.indexOf("("));
        }
        
        // Check if last segment starts with lowercase (likely a method)
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot > 0 && lastDot < normalized.length() - 1) {
            String lastSegment = normalized.substring(lastDot + 1);
            if (!lastSegment.isEmpty() && Character.isLowerCase(lastSegment.charAt(0))) {
                normalized = normalized.substring(0, lastDot);
            }
        }
        
        return normalized;
    }
}
