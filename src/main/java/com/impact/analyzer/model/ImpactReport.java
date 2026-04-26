package com.impact.analyzer.model;

import lombok.Data;

import java.util.List;

@Data
public class ImpactReport {
    private String prNumber;
    private String summary;
    private List<ImpactedComponent> impactedComponents;
    private List<String> affectedAPIs;
    private List<String> affectedUIScreens;
    private List<String> recommendedTests;
    private RiskLevel riskLevel;
    private List<String> affectedServices;
    
    @Data
    public static class ImpactedComponent {
        private String name;
        private String type; // SERVICE, API, UI, DB
        private String impactLevel; // HIGH, MEDIUM, LOW
        private String reason;
        private List<String> dependents;
    }
    
    public enum RiskLevel {
        HIGH, MEDIUM, LOW
    }
}
