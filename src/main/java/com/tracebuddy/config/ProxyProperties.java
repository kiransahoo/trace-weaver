package com.tracebuddy.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "proxy")
public class ProxyProperties {
    private boolean enabled;
    private String host;
    private int port;
}
