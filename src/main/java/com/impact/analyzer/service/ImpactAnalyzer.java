package com.impact.analyzer.service;

import com.impact.analyzer.model.DependencyGraph;
import com.impact.analyzer.model.ImpactReport;
import com.impact.analyzer.model.NodeMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class ImpactAnalyzer {
    
    public ImpactReport analyzeImpact(DependencyGraph graph, List<String> changedFiles) {
        ImpactReport report = new ImpactReport();
        
        // Find impacted nodes through reverse traversal
        Set<String> impactedNodes = new HashSet<>();
        
        for (String changedFile : changedFiles) {
            // Find which nodes in graph correspond to this file
            Set<String> affectedNodes = findNodesByFile(graph, changedFile);
            
            for (String node : affectedNodes) {
                // Perform reverse dependency traversal
                Set<String> impacted = graph.getAllImpactedNodes(node);
                impactedNodes.addAll(impacted);
                
                log.info("Changed node: {} impacts {} upstream nodes", node, impacted.size());
            }
        }
        
        // Categorize impacted nodes
        for (String node : impactedNodes) {
            NodeMetadata metadata = graph.getMetadata().get(node);
            if (metadata != null) {
                categorizeImpact(node, metadata, report);
            } else {
                // Heuristic-based categorization
                categorizeByNaming(node, report);
            }
        }
        
        // Generate test cases based on impact
        generateTestCases(report);
        
        // Calculate risk scores
        calculateRiskScores(report);
        
        return report;
    }
    
    private Set<String> findNodesByFile(DependencyGraph graph, String filePath) {
        Set<String> nodes = new HashSet<>();
        for (Map.Entry<String, NodeMetadata> entry : graph.getMetadata().entrySet()) {
            NodeMetadata metadata = entry.getValue();
            String nodeFilePath = metadata != null ? metadata.getFilePath() : null;
            if (nodeFilePath != null && filePath != null && nodeFilePath.contains(filePath)) {
                nodes.add(entry.getKey());
            }
        }
        return nodes;
    }
    
    private void categorizeImpact(String node, NodeMetadata metadata, ImpactReport report) {
        switch (metadata.getType().toUpperCase()) {
            case "SERVICE":
                report.getImpactedServices().add(node);
                break;
            case "API":
                report.getImpactedApis().add(node);
                if (metadata.getCustomProps().containsKey("path")) {
                    report.getRiskScores().put(node, 8); // High risk for APIs
                }
                break;
            case "UI_COMPONENT":
                report.getImpactedUiComponents().add(node);
                break;
            case "API_CALL":
                report.getImpactedApis().add(node);
                break;
        }
    }
    
    private void categorizeByNaming(String node, ImpactReport report) {
        if (node.matches(".*(Service|Repository|Impl).*")) {
            report.getImpactedServices().add(node);
        } else if (node.matches(".*(Controller|Endpoint|Resource).*") || 
                   node.contains("/api/")) {
            report.getImpactedApis().add(node);
        } else if (node.matches(".*(Component|Directive|Pipe).*")) {
            report.getImpactedUiComponents().add(node);
        } else if (node.matches(".*(Table|Column|Entity).*")) {
            report.getImpactedDatabaseTables().add(node);
        }
    }
    
    private void generateTestCases(ImpactReport report) {
        // Unit tests for changed services
        for (String service : report.getImpactedServices()) {
            ImpactReport.TestCase testCase = new ImpactReport.TestCase();
            testCase.setName("Unit test for " + service);
            testCase.setType("UNIT");
            testCase.setPriority("HIGH");
            testCase.setAffectedComponents(Collections.singletonList(service));
            report.getRequiredTestCases().add(testCase);
        }
        
        // Integration tests for APIs
        for (String api : report.getImpactedApis()) {
            ImpactReport.TestCase testCase = new ImpactReport.TestCase();
            testCase.setName("Integration test for " + api);
            testCase.setType("INTEGRATION");
            testCase.setPriority("HIGH");
            testCase.setAffectedComponents(Collections.singletonList(api));
            report.getRequiredTestCases().add(testCase);
        }
        
        // E2E tests for UI components
        if (!report.getImpactedUiComponents().isEmpty()) {
            ImpactReport.TestCase e2eTest = new ImpactReport.TestCase();
            e2eTest.setName("E2E test for impacted UI flows");
            e2eTest.setType("E2E");
            e2eTest.setPriority("MEDIUM");
            e2eTest.setAffectedComponents(report.getImpactedUiComponents());
            report.getRequiredTestCases().add(e2eTest);
        }
    }
    
    private void calculateRiskScores(ImpactReport report) {
        int serviceImpact = report.getImpactedServices().size();
        int apiImpact = report.getImpactedApis().size();
        int uiImpact = report.getImpactedUiComponents().size();
        
        report.getRiskScores().put("OVERALL_RISK", 
            serviceImpact * 10 + apiImpact * 8 + uiImpact * 5);
        
        report.getRiskScores().put("BREAKING_CHANGE_RISK", 
            apiImpact > 3 ? 9 : (apiImpact > 0 ? 5 : 1));
    }
}