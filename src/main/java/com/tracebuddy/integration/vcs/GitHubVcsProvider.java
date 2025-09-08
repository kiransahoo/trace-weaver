package com.tracebuddy.integration.vcs;

import com.tracebuddy.config.GitHubProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.springframework.http.*;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GitHub implementation of VCS Provider
 * @author kiransahoo
 */
@Slf4j
@Service
public class GitHubVcsProvider implements VcsProvider {

    private static final String BASE_URL = "https://api.github.com";
    
    private final RestClient restClient;
    private final GitHubProperties githubProperties;
    
    @Value("${github.owner}")
    private String defaultOwner;
    
    @Value("${github.repo}")
    private String defaultRepo;
    
    @Value("${github.branch:main}")
    private String defaultBranch;
    
    @Value("${github.token:#{null}}")
    private String defaultToken;
    
    // Current repository configuration
    private Map<String, String> currentRepoConfig;
    
    @Autowired
    public GitHubVcsProvider(GitHubProperties githubProperties) {
        this.githubProperties = githubProperties;
        
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .defaultHeader("User-Agent", "TraceBuddy/1.0");
        
        this.restClient = builder.build();
    }
    
    @PostConstruct
    public void init() {
        log.info("GitHub VCS Provider initialized");
        log.info("Default owner: {}, repo: {}, branch: {}", defaultOwner, defaultRepo, defaultBranch);
    }
    
    public void setRepoConfig(Map<String, String> repoConfig) {
        this.currentRepoConfig = repoConfig;
    }
    
    @Override
    public String getProviderType() {
        return "GitHub";
    }
    
    @Override
    public boolean testConnection() {
        try {
            String uri = String.format("/repos/%s/%s", defaultOwner, defaultRepo);
            
            RestClient.RequestHeadersSpec<?> request = restClient.get().uri(uri);
            
            if (defaultToken != null && !defaultToken.isEmpty()) {
                request = request.header("Authorization", "Bearer " + defaultToken);
            }
            
            request.retrieve().body(Repository.class);
            return true;
        } catch (Exception e) {
            log.error("GitHub connection test failed", e);
            return false;
        }
    }
    
    @Override
    public String readFileByClassName(String className) {
        // Get repository config
        String owner = defaultOwner;
        String repo = defaultRepo;
        String branch = defaultBranch;
        String token = defaultToken;
        
        if (currentRepoConfig != null) {
            owner = currentRepoConfig.getOrDefault("owner", owner);
            repo = currentRepoConfig.getOrDefault("repo", repo);
            branch = currentRepoConfig.getOrDefault("branch", branch);
            token = currentRepoConfig.getOrDefault("token", token);
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
                "src/main/java/" + expectedPath + "/" + fileName,
                "src/test/java/" + expectedPath + "/" + fileName,
                expectedPath + "/" + fileName,
                fileName
        );
        
        for (String path : searchPaths) {
            path = path.replaceAll("//+", "/");
            String content = readGitHubFile(owner, repo, branch, token, path);
            if (!content.startsWith("File not found") && !content.startsWith("Error")) {
                return String.format("Repository: GitHub - %s/%s\n%s", owner, repo, content);
            }
        }
        
        // If not found, do recursive search
        log.info("File not found in common locations, starting recursive search...");
        String foundPath = findFileRecursiveInRepo(owner, repo, branch, token, "", fileName);
        
        if (foundPath != null) {
            log.info("Found file via recursive search at: {}", foundPath);
            String content = readGitHubFile(owner, repo, branch, token, foundPath);
            return String.format("Repository: GitHub - %s/%s\n%s", owner, repo, content);
        }
        
        return String.format("Could not find file: %s in GitHub repository: %s/%s", 
                fileName, owner, repo);
    }
    
    @Override
    public String readFile(String path) {
        String owner = defaultOwner;
        String repo = defaultRepo;
        String branch = defaultBranch;
        String token = defaultToken;
        
        return readGitHubFile(owner, repo, branch, token, path);
    }
    
    @Override
    public String listFiles(String directory) {
        try {
            String owner = defaultOwner;
            String repo = defaultRepo;
            String branch = defaultBranch;
            String token = defaultToken;
            
            String uri = String.format("/repos/%s/%s/contents/%s?ref=%s",
                    owner, repo, directory, branch);
            
            RestClient.RequestHeadersSpec<?> request = restClient.get().uri(uri);
            
            if (token != null && !token.isEmpty()) {
                request = request.header("Authorization", "Bearer " + token);
            }
            
            DirectoryContent[] contents = request.retrieve().body(DirectoryContent[].class);
            
            if (contents == null || contents.length == 0) {
                return "Directory not found or empty: " + directory;
            }
            
            StringBuilder result = new StringBuilder();
            result.append(String.format("Files in %s:\n\n", directory));
            
            for (DirectoryContent item : contents) {
                result.append(String.format("- %s (%s", item.name(), item.type()));
                if ("file".equals(item.type()) && item.size() != null) {
                    result.append(String.format(", %d bytes", item.size()));
                }
                result.append(")\n");
            }
            
            return result.toString();
            
        } catch (RestClientException e) {
            return String.format("Error listing directory %s: %s", directory, e.getMessage());
        }
    }
    
    @Override
    public String getRepositoryInfo() {
        try {
            String owner = defaultOwner;
            String repo = defaultRepo;
            String token = defaultToken;
            
            String uri = String.format("/repos/%s/%s", owner, repo);
            
            RestClient.RequestHeadersSpec<?> request = restClient.get().uri(uri);
            
            if (token != null && !token.isEmpty()) {
                request = request.header("Authorization", "Bearer " + token);
            }
            
            Repository repository = request.retrieve().body(Repository.class);
            
            if (repository == null) {
                return "Repository not found";
            }
            
            return String.format("""
                    Repository: %s
                    Description: %s
                    Default Branch: %s
                    Language: %s
                    Stars: %d
                    Forks: %d
                    Open Issues: %d
                    Created: %s
                    Last Updated: %s
                    """,
                    repository.fullName(),
                    repository.description() != null ? repository.description() : "N/A",
                    repository.defaultBranch(),
                    repository.language() != null ? repository.language() : "N/A",
                    repository.stars(),
                    repository.forks(),
                    repository.openIssues(),
                    repository.createdAt(),
                    repository.updatedAt()
            );
            
        } catch (RestClientException e) {
            return String.format("Error getting repository info: %s", e.getMessage());
        }
    }
    
    private String readGitHubFile(String owner, String repo, String branch, String token, String path) {
        try {
            String uri = String.format("/repos/%s/%s/contents/%s?ref=%s",
                    owner, repo, path, branch);
            
            RestClient.RequestHeadersSpec<?> request = restClient.get().uri(uri);
            
            if (token != null && !token.isEmpty()) {
                request = request.header("Authorization", "Bearer " + token);
            }
            
            FileContent fileContent = request.retrieve().body(FileContent.class);
            
            if (fileContent == null || fileContent.content() == null) {
                return "File not found: " + path;
            }
            
            String content = decodeBase64(fileContent.content());
            
            return String.format("""
                File: %s
                Size: %d bytes
                
                Content:
                %s
                """, path, content.length(), content);
            
        } catch (HttpClientErrorException.NotFound e) {
            return "File not found: " + path;
        } catch (RestClientException e) {
            return String.format("Error reading file %s: %s", path, e.getMessage());
        }
    }
    
    private String findFileRecursiveInRepo(String owner, String repo, String branch,
                                           String token, String currentPath, String fileName) {
        try {
            String uri = String.format("/repos/%s/%s/contents/%s?ref=%s",
                    owner, repo, currentPath, branch);
            
            RestClient.RequestHeadersSpec<?> request = restClient.get().uri(uri);
            
            if (token != null && !token.isEmpty()) {
                request = request.header("Authorization", "Bearer " + token);
            }
            
            DirectoryContent[] contents = request.retrieve().body(DirectoryContent[].class);
            
            if (contents == null) {
                return null;
            }
            
            for (DirectoryContent item : contents) {
                String itemPath = currentPath.isEmpty() ? item.name() : currentPath + "/" + item.name();
                
                if ("file".equals(item.type()) && item.name().equals(fileName)) {
                    // Found the file
                    return itemPath;
                } else if ("dir".equals(item.type())) {
                    // Skip directories we know won't contain source files
                    if (!shouldSkipDirectory(item.name())) {
                        String found = findFileRecursiveInRepo(owner, repo, branch,
                                token, itemPath, fileName);
                        if (found != null) {
                            return found;
                        }
                    }
                }
            }
            
            return null;
            
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (Exception e) {
            log.debug("Error searching directory {}: {}", currentPath, e.getMessage());
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
    
    private String decodeBase64(String base64Content) {
        String cleanBase64 = base64Content.replaceAll("\\s", "");
        byte[] decodedBytes = Base64.getDecoder().decode(cleanBase64);
        return new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
    }
    
    // Data classes
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileContent(@JsonProperty("content") String content,
                              @JsonProperty("encoding") String encoding,
                              @JsonProperty("size") Long size,
                              @JsonProperty("name") String name,
                              @JsonProperty("path") String path,
                              @JsonProperty("type") String type) {
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DirectoryContent(@JsonProperty("name") String name,
                                   @JsonProperty("path") String path,
                                   @JsonProperty("type") String type,
                                   @JsonProperty("size") Long size) {
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(@JsonProperty("name") String name,
                             @JsonProperty("full_name") String fullName,
                             @JsonProperty("description") String description,
                             @JsonProperty("default_branch") String defaultBranch,
                             @JsonProperty("language") String language,
                             @JsonProperty("stargazers_count") Integer stars,
                             @JsonProperty("forks_count") Integer forks,
                             @JsonProperty("open_issues_count") Integer openIssues,
                             @JsonProperty("created_at") String createdAt,
                             @JsonProperty("updated_at") String updatedAt) {
    }
}
