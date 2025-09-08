package com.tracebuddy.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(name = "mcp.source", havingValue = "filesystem", matchIfMissing = false)
public class McpConfiguration {

    private final McpProperties mcpProperties;

    @Bean(destroyMethod = "close")
    public McpSyncClient mcpClient() {
        try {
            // Resolve the base path
            Path basePath;
            if (Paths.get(mcpProperties.getBasePath()).isAbsolute()) {
                basePath = Paths.get(mcpProperties.getBasePath());
            } else {
                // If relative, resolve from current working directory
                basePath = Paths.get(System.getProperty("user.dir"), mcpProperties.getBasePath());
            }

            String fullBasePath = basePath.toAbsolutePath().normalize().toString();
            log.info("MCP base path: {}", fullBasePath);

            // Log all configured source paths
            log.info("MCP source paths:");
            mcpProperties.getSourcePaths().forEach((key, value) -> {
                Path fullPath = basePath.resolve(value);
                log.info("  {} -> {} (full: {})", key, value, fullPath.toAbsolutePath());

                // Verify paths exist
                if (!fullPath.toFile().exists()) {
                    log.warn("  Path does not exist: {}", fullPath.toAbsolutePath());
                }
            });

            // MCP filesystem server will serve from the base path
            var stdioParams = ServerParameters.builder("npx")
                    .args("-y", "@modelcontextprotocol/server-filesystem", fullBasePath)
                    .build();

            var mcpClient = McpClient.sync(new StdioClientTransport(stdioParams))
                    .requestTimeout(Duration.ofSeconds(mcpProperties.getTimeoutSeconds()))
                    .build();

            var init = mcpClient.initialize();
            log.info("MCP Initialized: {}", init);
            log.info("MCP filesystem server is serving files from base path: {}", fullBasePath);

            return mcpClient;
        } catch (Exception e) {
            log.error("Failed to initialize MCP client", e);
            throw new RuntimeException("MCP initialization failed", e);
        }
    }
}
