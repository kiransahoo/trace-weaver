package com.tracebuddy.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Profile("docker")
public class ApiPrefixConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // Add /api prefix to all REST controllers
        configurer.addPathPrefix("/api",
            c -> c.isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class));
    }
}
