package com.impact.analyzer.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class ImpactReport {
    private List<String> impactedServices = new ArrayList<>();
    private List<String> impactedApis = new ArrayList<>();
    private List<String> impactedUiComponents = new ArrayList<>();
    private List<String> impactedDatabaseTables = new ArrayList<>();
    private List<TestCase> requiredTestCases = new ArrayList<>();
    private Map<String, Integer> riskScores = new HashMap<>();

    @Data
    public static class TestCase {
        private String name;
        private String type; // UNIT, INTEGRATION, E2E
        private String priority; // HIGH, MEDIUM, LOW
        private List<String> affectedComponents;
    }
}