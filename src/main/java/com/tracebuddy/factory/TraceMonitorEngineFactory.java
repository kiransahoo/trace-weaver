package com.tracebuddy.factory;

import com.tracebuddy.engine.TraceMonitorEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

@Slf4j
@Component
public class TraceMonitorEngineFactory {

    @Autowired
    private TraceMonitorEngine engine;  // Spring will inject the right one based on @ConditionalOnProperty

    @PostConstruct
    public void init() {
        log.info("TraceBuddy engine initialized: {}", engine.getEngineType());
    }

    public TraceMonitorEngine getEngine() {
        return engine;
    }
}