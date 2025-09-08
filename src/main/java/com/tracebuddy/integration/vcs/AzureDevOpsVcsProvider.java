package com.tracebuddy.integration.vcs;

import com.tracebuddy.config.ProxyProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.springframework.http.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Azure DevOps implementation of VCS Provider
 * @author kiransahoo
 */
@Slf4j
@Service
public class AzureDevOpsVcsProvider implements VcsProvider {

    @Autowired(required = false)
    private ProxyProperties proxyProperties;

    // Current repository configuration
    private Map<String, String> currentRepoConfig;

    public void setRepoConfig(Map<String, String> repoConfig) {
        this.currentRepoConfig = repoConfig;
    }

    @Override
    public String getProviderType() {
        return "Azure DevOps";
    }

    @Override
    public boolean testConnection() {
        if (currentRepoConfig == null) {
            return false;
        }
        
        try {
            String organization = currentRepoConfig.get("organization");
            String project = currentRepoConfig.get("project");
            String repo = currentRepoConfig.get("repo");
            String token = currentRepoConfig.get("token");
            
            if (organization == null || project == null || repo == null) {
                return false;
            }
            
            // Test by reading root directory
            String result = readAzureDevOpsFile(organization, project, repo, "main", token, "/");
            return !result.startsWith("Error");
            
        } catch (Exception e) {
            log.error("Azure DevOps connection test failed", e);
            return false;
        }
    }

    @Override
    public String readFileByClassName(String className) {
        if (currentRepoConfig == null) {
            return "Error: No Azure DevOps configuration available";
        }
        
        String organization = currentRepoConfig.get("organization");
        String project = currentRepoConfig.get("project");
        String repoName = currentRepoConfig.get("repo");
        String branch = currentRepoConfig.getOrDefault("branch", "main");
        String token = currentRepoConfig.get("token");
        
        // Check for hardcoded base URL
        String baseUrl = currentRepoConfig.get("baseUrl");
        if (baseUrl != null && !baseUrl.isEmpty()) {
            log.info("Using hardcoded base URL: {}", baseUrl);
            return searchWithHardcodedBaseUrl(currentRepoConfig, className);
        }
        
        // Convert className to file info
        String fileName;
        String expectedPath = "";
        
        int lastDotIndex = className.lastIndexOf('.');
        if (lastDotIndex > 0) {
            fileName = className.substring(lastDotIndex + 1) + ".java";
            expectedPath = className.substring(0, lastDotIndex).replace('.', '/');
        } else {
            fileName = className + ".java";
        }
        
        // Try common paths first
        List<String> searchPaths = Arrays.asList(
                "/src/main/java/" + expectedPath + "/" + fileName,
                "/src/java/" + expectedPath + "/" + fileName,
                "/src/test/java/" + expectedPath + "/" + fileName,
                "/" + expectedPath + "/" + fileName,
                "/" + fileName
        );
        
        for (String path : searchPaths) {
            path = path.replaceAll("//+", "/");
            String content = readAzureDevOpsFile(organization, project, repoName, branch, token, path);
            if (!content.startsWith("File not found") && !content.startsWith("Error")) {
                return String.format("Repository: Azure DevOps - %s/%s/%s\n%s",
                        organization, project, repoName, content);
            }
        }
        
        // If not found, do recursive search
        log.info("File not found in common locations, starting recursive search in Azure DevOps...");
        String foundPath = findFileRecursiveInAzureDevOps(organization, project, repoName,
                branch, token, "/", fileName);
        
        if (foundPath != null) {
            log.info("Found file via recursive search at: {}", foundPath);
            String content = readAzureDevOpsFile(organization, project, repoName, branch, token, foundPath);
            return String.format("Repository: Azure DevOps - %s/%s/%s\n%s",
                    organization, project, repoName, content);
        }
        
        return String.format("Could not find file: %s in Azure DevOps repository: %s/%s/%s",
                fileName, organization, project, repoName);
    }

    @Override
    public String readFile(String path) {
        if (currentRepoConfig == null) {
            return "Error: No Azure DevOps configuration available";
        }
        
        String organization = currentRepoConfig.get("organization");
        String project = currentRepoConfig.get("project");
        String repoName = currentRepoConfig.get("repo");
        String branch = currentRepoConfig.getOrDefault("branch", "main");
        String token = currentRepoConfig.get("token");
        
        return readAzureDevOpsFile(organization, project, repoName, branch, token, "/" + path);
    }

    @Override
    public String listFiles(String directory) {
        if (currentRepoConfig == null) {
            return "Error: No Azure DevOps configuration available";
        }
        
        try {
            String organization = currentRepoConfig.get("organization");
            String project = currentRepoConfig.get("project");
            String repoName = currentRepoConfig.get("repo");
            String branch = currentRepoConfig.getOrDefault("branch", "main");
            String token = currentRepoConfig.get("token");
            
            String encodedProject = URLEncoder.encode(project, StandardCharsets.UTF_8);
            String encodedRepo = URLEncoder.encode(repoName, StandardCharsets.UTF_8);
            
            String baseUrl = String.format("https://dev.azure.com/%s/%s/_apis/git/repositories/%s/items",
                    organization, encodedProject, encodedRepo);
            
            String uri = baseUrl + "?scopePath=" + URLEncoder.encode("/" + directory, StandardCharsets.UTF_8) +
                    "&recursionLevel=OneLevel" +
                    "&versionDescriptor.version=" + branch +
                    "&api-version=7.0";
            
            RestClient azureClient = RestClient.builder().baseUrl("").build();
            RestClient.RequestHeadersSpec<?> request = azureClient.get().uri(uri);
            
            if (token != null && !token.isEmpty()) {
                String auth = Base64.getEncoder().encodeToString((":" + token).getBytes(StandardCharsets.UTF_8));
                request = request.header("Authorization", "Basic " + auth);
            }
            
            Map<String, Object> response = request.retrieve().body(Map.class);
            List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("value");
            
            if (items == null || items.isEmpty()) {
                return "Directory not found or empty: " + directory;
            }
            
            StringBuilder result = new StringBuilder();
            result.append(String.format("Files in %s:\n\n", directory));
            
            for (Map<String, Object> item : items) {
                String itemPath = (String) item.get("path");
                Boolean isFolder = (Boolean) item.get("isFolder");
                String name = itemPath.substring(itemPath.lastIndexOf('/') + 1);
                
                result.append(String.format("- %s (%s)\n", name, 
                        Boolean.TRUE.equals(isFolder) ? "directory" : "file"));
            }
            
            return result.toString();
            
        } catch (Exception e) {
            return String.format("Error listing directory %s: %s", directory, e.getMessage());
        }
    }

    @Override
    public String getRepositoryInfo() {
        if (currentRepoConfig == null) {
            return "Error: No Azure DevOps configuration available";
        }
        
        String organization = currentRepoConfig.get("organization");
        String project = currentRepoConfig.get("project");
        String repoName = currentRepoConfig.get("repo");
        
        return String.format("""
                Repository: Azure DevOps
                Organization: %s
                Project: %s
                Repository: %s
                """, organization, project, repoName);
    }

    private String readAzureDevOpsFile(String organization, String project, String repoName,
                                       String branch, String token, String path) {
        try {
            String encodedProject = URLEncoder.encode(project, StandardCharsets.UTF_8);
            String encodedRepo = URLEncoder.encode(repoName, StandardCharsets.UTF_8);
            
            String baseUrl = String.format("https://%s.visualstudio.com/%s/_apis/git/repositories/%s/items",
                    organization, encodedProject, encodedRepo);
            
            String uri = baseUrl + "?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8) +
                    "&versionDescriptor.version=" + branch +
                    "&api-version=7.0";
            
            log.info("Azure DevOps API URL: {}", uri);
            
            RestClient azureClient = RestClient.builder()
                    .baseUrl("")
                    .build();
            
            RestClient.RequestHeadersSpec<?> request = azureClient.get().uri(uri);
            
            if (token != null && !token.isEmpty()) {
                String auth = Base64.getEncoder().encodeToString((":" + token).getBytes(StandardCharsets.UTF_8));
                request = request.header("Authorization", "Basic " + auth);
            }
            
            String content = request.retrieve().body(String.class);
            
            return String.format("""
            File: %s
            Size: %d bytes
            
            Content:
            %s
            """, path, content.length(), content);
            
        } catch (HttpClientErrorException.NotFound e) {
            return "File not found: " + path;
        } catch (RestClientException e) {
            log.error("Error reading from Azure DevOps: {}", e.getMessage());
            return String.format("Error reading file %s: %s", path, e.getMessage());
        }
    }

    private String searchWithHardcodedBaseUrl(Map<String, String> repoConfig, String className) {
        String baseUrl = repoConfig.get("baseUrl");
        String token = repoConfig.get("token");
        String branch = repoConfig.getOrDefault("branch", "master");
        String mappedPackage = repoConfig.get("packageName");
        
        log.info("Using hardcoded base URL for class: {} with mapped package: {}", className, mappedPackage);
        
        // Extract the part after the mapped package
        String relativePath = "";
        if (className.startsWith(mappedPackage + ".")) {
            relativePath = className.substring(mappedPackage.length() + 1).replace('.', '/') + ".java";
        }
        
        if (mappedPackage.startsWith("com.")) {
            String afterCom = mappedPackage.substring(4);
            if (!afterCom.isEmpty()) {
                relativePath = afterCom.replace('.', '/') + "/" + relativePath;
            }
        }
        
        // Simply append the relative path to the base URL
        String completeUrl = baseUrl + URLEncoder.encode("/" + relativePath, StandardCharsets.UTF_8) +
                "&versionDescriptor.version=" + branch + "&api-version=6.0";
        
        log.info("Constructed URL: {}", completeUrl);
        
        try {
            // Build HttpClient with proxy if enabled
            HttpClientBuilder clientBuilder = HttpClientBuilder.create()
                    .setDefaultRequestConfig(RequestConfig.custom()
                            .setConnectTimeout(30000)
                            .setSocketTimeout(30000)
                            .build());
            
            // Add proxy from configuration if enabled
            if (proxyProperties != null && proxyProperties.isEnabled()) {
                HttpHost proxy = new HttpHost(proxyProperties.getHost(), proxyProperties.getPort());
                clientBuilder.setProxy(proxy);
                log.info("Using proxy: {}:{}", proxyProperties.getHost(), proxyProperties.getPort());
            }
            
            CloseableHttpClient httpClient = clientBuilder.build();
            
            HttpGet request = new HttpGet(completeUrl);
            request.addHeader("Accept", "text/plain");
            
            if (token != null && !token.isEmpty()) {
                String auth = Base64.getEncoder().encodeToString((":" + token).getBytes(StandardCharsets.UTF_8));
                request.addHeader("Authorization", "Basic " + auth);
            }
            
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                
                if (statusCode == 200) {
                    String content = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                    
                    return String.format("""
                    File: %s
                    Repository: Azure DevOps
                    Size: %d bytes
                    
                    Content:
                    %s
                    """, className + ".java", content.length(), content);
                } else {
                    String error = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                    log.error("HTTP Error {}: {}", statusCode, error);
                    return String.format("Error: HTTP %d - %s", statusCode, error);
                }
            }
            
        } catch (Exception e) {
            log.error("Error fetching from hardcoded URL: {}", e.getMessage(), e);
            return String.format("Error: Failed to fetch %s - %s", className, e.getMessage());
        }
    }

    private String findFileRecursiveInAzureDevOps(String organization, String project,
                                                  String repoName, String branch, String token,
                                                  String currentPath, String fileName) {
        try {
            String encodedProject = URLEncoder.encode(project, StandardCharsets.UTF_8);
            String encodedRepo = URLEncoder.encode(repoName, StandardCharsets.UTF_8);
            
            String baseUrl = String.format("https://dev.azure.com/%s/%s/_apis/git/repositories/%s/items",
                    organization, encodedProject, encodedRepo);
            
            String uri = baseUrl + "?scopePath=" + URLEncoder.encode(currentPath, StandardCharsets.UTF_8) +
                    "&recursionLevel=OneLevel" +
                    "&versionDescriptor.version=" + branch +
                    "&api-version=7.0";
            
            RestClient azureClient = RestClient.builder().baseUrl("").build();
            RestClient.RequestHeadersSpec<?> request = azureClient.get().uri(uri);
            
            if (token != null && !token.isEmpty()) {
                String auth = Base64.getEncoder().encodeToString((":" + token).getBytes(StandardCharsets.UTF_8));
                request = request.header("Authorization", "Basic " + auth);
            }
            
            Map<String, Object> response = request.retrieve().body(Map.class);
            List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("value");
            
            if (items != null) {
                for (Map<String, Object> item : items) {
                    String itemPath = (String) item.get("path");
                    Boolean isFolder = (Boolean) item.get("isFolder");
                    
                    if (Boolean.FALSE.equals(isFolder) && itemPath.endsWith("/" + fileName)) {
                        // Found the file
                        return itemPath;
                    } else if (Boolean.TRUE.equals(isFolder)) {
                        // Skip directories we know won't contain source files
                        String folderName = itemPath.substring(itemPath.lastIndexOf('/') + 1);
                        if (!shouldSkipDirectory(folderName)) {
                            String found = findFileRecursiveInAzureDevOps(organization, project, repoName,
                                    branch, token, itemPath, fileName);
                            if (found != null) {
                                return found;
                            }
                        }
                    }
                }
            }
            
            return null;
            
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (Exception e) {
            log.debug("Error searching Azure DevOps directory {}: {}", currentPath, e.getMessage());
            return null;
        }
    }

    private boolean shouldSkipDirectory(String dirName) {
        return dirName.equals(".git") ||
                dirName.equals("target") ||
                dirName.equals("build") ||
                dirName.equals("node_modules") ||
                dirName.equals("bin") ||
                dirName.equals("obj") ||
                dirName.equals(".vs") ||
                dirName.equals("packages") ||
                dirName.startsWith(".");
    }
}
