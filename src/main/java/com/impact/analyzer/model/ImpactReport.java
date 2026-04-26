package com.impact.analyzer.model;

import lombok.Data;

import java.util.List;

@Data
public class ImpactReport {
    private String prNumber;
    private String prTitle;
    private ImpactReport.AnalysisSummary summary;
    private List<ImpactReport.ImpactedService> impactedServices;
    private List<ImpactReport.ImpactedAPI> impactedApis;
    private List<ImpactReport.ImpactedScreen> impactedScreens;
    private List<String> requiredTestCases;
    private List<String> recommendations;
    private double confidenceScore;
    private String aiAnalysis;

    @Data
    public static class AnalysisSummary {
        private int totalChanges;
        private int riskLevel; // 1-5
        private String riskDescription;
        private int estimatedTestingHours;
    }

    @Data
    public static class ImpactedService {
        private String name;
        private String impactType; // DIRECT, INDIRECT
        private String risk;
        private List<String> reasons;
    }

    @Data
    public static class ImpactedAPI {
        private String endpoint;
        private String method;
        private String impactType;
        private List<String> impactedMethods;
    }

    @Data
    public static class ImpactedScreen {
        private String name;
        private String component;
        private String impactReason;
    }
}
