package com.tracebuddy.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {
    private boolean enabled = false;
    private String basePath = ".";
    private int timeoutSeconds = 10;
    private Map<String, String> sourcePaths = new HashMap<>();
    private String source = "github";

    // Helper method to get full path for a source type
    public String getFullPath(String sourceType) {
        String relativePath = sourcePaths.get(sourceType);
        if (relativePath == null) {
            return null;
        }
        return relativePath;
    }

    // Helper method to convert class name to file path
    public String getJavaFilePath(String className) {
        String javaPath = sourcePaths.get("java");
        if (javaPath == null) {
            javaPath = "src/main/java";
        }
        return javaPath + "/" + className.replace('.', '/') + ".java";
    }

    // Helper method to get resource file path
    public String getResourcePath(String resourceName) {
        String resourcePath = sourcePaths.get("resources");
        if (resourcePath == null) {
            resourcePath = "src/main/resources";
        }
        return resourcePath + "/" + resourceName;
    }
}
