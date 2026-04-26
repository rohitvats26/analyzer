package com.impact.analyzer.service;

import com.impact.analyzer.model.ChangedFile;
import com.impact.analyzer.model.DependencyNode;
import com.impact.analyzer.model.ImpactReport;
import com.impact.analyzer.model.PullRequestEvent;
import com.impact.analyzer.service.AIService;
import com.impact.analyzer.service.DependencyGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ImpactAnalysisService {

    @Autowired
    private DependencyGraphService dependencyGraphService;

    @Autowired
    private AIService aiService;

    public ImpactReport analyzePullRequest(PullRequestEvent event, List<ChangedFile> changedFiles) {
        log.info("Analyzing PR #{} with {} changed files",
                event.getPullRequest().getNumber(), changedFiles.size());

        // Find all impacted nodes from dependency graph
        Set<String> impactedNodeIds = new HashSet<>();
        for (ChangedFile file : changedFiles) {
            Set<String> impacted = dependencyGraphService.findImpactedNodes(file.getFilename());
            impactedNodeIds.addAll(impacted);
        }

        List<DependencyNode> impactedNodes = dependencyGraphService.getImpactedNodesDetailed(impactedNodeIds);

        log.info("Found {} impacted components", impactedNodes.size());

        // Use AI for intelligent analysis
        ImpactReport report = aiService.analyzeImpact(changedFiles, impactedNodes);

        // Add additional metadata
        report.setPrNumber(String.valueOf(event.getPullRequest().getNumber()));
        report.setPrTitle(event.getPullRequest().getTitle());

        return report;
    }

    public Map<String, Object> getRiskAssessment(ImpactReport report) {
        Map<String, Object> assessment = new HashMap<>();

        int riskScore = report.getSummary().getRiskLevel();
        assessment.put("overallRisk", riskScore);
        assessment.put("shouldBlockMerge", riskScore >= 4);
        assessment.put("requiresReview", riskScore >= 3);
        assessment.put("estimatedDeploymentRisk", getRiskDescription(riskScore));

        // Analyze specific risks
        List<String> specificRisks = new ArrayList<>();

        if (report.getImpactedServices().stream().anyMatch(s -> "HIGH".equals(s.getRisk()))) {
            specificRisks.add("Critical services affected");
        }

        if (report.getImpactedApis().size() > 3) {
            specificRisks.add("Multiple API endpoints impacted - potential breaking changes");
        }

        if (report.getSummary().getTotalChanges() > 10) {
            specificRisks.add("Large number of files changed - increased merge complexity");
        }

        assessment.put("specificRisks", specificRisks);
        assessment.put("testingEffort", report.getSummary().getEstimatedTestingHours());

        return assessment;
    }

    private String getRiskDescription(int riskLevel) {
        switch (riskLevel) {
            case 1: return "VERY_LOW - Safe to deploy automatically";
            case 2: return "LOW - Minimal risk, quick testing recommended";
            case 3: return "MEDIUM - Standard review and testing required";
            case 4: return "HIGH - Thorough testing and approval needed";
            case 5: return "CRITICAL - Requires extensive review and staging validation";
            default: return "UNKNOWN";
        }
    }
}