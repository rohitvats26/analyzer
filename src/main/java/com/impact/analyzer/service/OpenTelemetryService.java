package com.impact.analyzer.service;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class OpenTelemetryService {
    
    private final Tracer tracer;
    private final ConcurrentHashMap<String, RuntimeDependency> runtimeDeps = new ConcurrentHashMap<>();
    
    public OpenTelemetryService(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("pr-impact-analyzer");
    }
    
    public void captureDependency(String caller, String callee, String method) {
        Span span = tracer.spanBuilder("dependency." + caller + "->" + callee)
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("caller", caller);
            span.setAttribute("callee", callee);
            span.setAttribute("method", method);
            span.setAttribute("timestamp", System.currentTimeMillis());
            
            // Capture runtime dependency in our store
            String key = caller + "->" + callee;
            runtimeDeps.computeIfAbsent(key, k -> {
                RuntimeDependency dep = new RuntimeDependency();
                dep.setCaller(caller);
                dep.setCallee(callee);
                dep.setCount(0);
                return dep;
            });
            runtimeDeps.get(key).increment();
            
            log.info("Captured runtime dependency: {} -> {}", caller, callee);
        } finally {
            span.end();
        }
    }
    
    public ConcurrentHashMap<String, RuntimeDependency> getRuntimeDependencies() {
        return runtimeDeps;
    }
    
    @Setter
    @Getter
    public static class RuntimeDependency {
        // getters/setters
        private String caller;
        private String callee;
        private int count;
        
        public synchronized void increment() { this.count++; }

    }
}