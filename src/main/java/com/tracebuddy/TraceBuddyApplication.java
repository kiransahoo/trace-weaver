package com.tracebuddy;

import com.tracebuddy.config.GitHubProperties;
import com.tracebuddy.config.McpProperties;
import com.tracebuddy.integration.vcs.VcsService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * TraceBuddy - Modular OTEL Trace Analyzer
 * @author kiransahoo
 */
@SpringBootApplication
@EnableConfigurationProperties({GitHubProperties.class, McpProperties.class})
public class TraceBuddyApplication {

    public static void main(String[] args) {
        SpringApplication.run(TraceBuddyApplication.class, args);
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    @Bean
    public ToolCallbackProvider vcsTools(VcsService vcsService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(vcsService)
                .build();
    }
}
