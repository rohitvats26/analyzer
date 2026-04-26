/*
package com.impact.analyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.impact.analyzer.model.ChangedFile;
import com.impact.analyzer.model.DependencyNode;
import com.impact.analyzer.model.ImpactReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AIService {

    @Value("${github.token:}")
    private String githubToken;

    @Value("${copilot.use.local:true}")
    private boolean useLocalAnalysis;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImpactReport analyzeImpact(List<ChangedFile> changedFiles,
                                      List<DependencyNode> impactedNodes) {
        // Use local analysis by default (no API calls)
        if (useLocalAnalysis) {
            log.info("Using local analysis (no API calls)");
            return generateLocalAnalysis(changedFiles, impactedNodes);
        }

        // Optionally try GitHub Copilot API if configured
        if (githubToken != null && !githubToken.isEmpty()) {
            try {
                return analyzeWithCopilot(changedFiles, impactedNodes);
            } catch (Exception e) {
                log.warn("Copilot analysis failed, using local analysis", e);
                return generateLocalAnalysis(changedFiles, impactedNodes);
            }
        }

        return generateLocalAnalysis(changedFiles, impactedNodes);
    }

    private ImpactReport analyzeWithCopilot(List<ChangedFile> changedFiles,
                                            List<DependencyNode> impactedNodes) throws Exception {
        log.info("Attempting to analyze with GitHub Copilot...");

        // GitHub Copilot API endpoint (requires GitHub token with Copilot access)
        String apiUrl = "https://api.github.com/copilot/completions";

        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "token " + githubToken);
        conn.setRequestProperty("User-Agent", "PR-Impact-Analyzer");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        String prompt = buildCopilotPrompt(changedFiles, impactedNodes);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("prompt", prompt);
        requestBody.put("max_tokens", 500);
        requestBody.put("temperature", 0.3);

        String jsonInput = objectMapper.writeValueAsString(requestBody);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonInput.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }

            JsonNode responseJson = objectMapper.readTree(response.toString());
            String aiResponse = responseJson.get("choices").get(0).get("text").asText();
            return parseAIResponse(aiResponse, changedFiles, impactedNodes);
        } else {
            log.warn("Copilot API returned {}, using local analysis", responseCode);
            throw new RuntimeException("Copilot API error: " + responseCode);
        }
    }

    private String buildCopilotPrompt(List<ChangedFile> changedFiles, List<DependencyNode> impactedNodes) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze the impact of these code changes and return a JSON response.\n\n");
        prompt.append("Changed files:\n");
        for (ChangedFile file : changedFiles) {
            prompt.append(String.format("- %s: +%d -%d\n",
                    file.getFilename(), file.getAdditions(), file.getDeletions()));
        }

        prompt.append("\nImpacted components:\n");
        for (DependencyNode node : impactedNodes) {
            prompt.append(String.format("- %s (%s)\n", node.getName(), node.getType()));
        }

        prompt.append("\nReturn JSON with: summary (riskLevel 1-5, riskDescription, estimatedTestingHours), ");
        prompt.append("impactedServices, impactedApis, impactedScreens, requiredTestCases, recommendations\n");

        return prompt.toString();
    }

    private ImpactReport generateLocalAnalysis(List<ChangedFile> changedFiles,
                                               List<DependencyNode> impactedNodes) {
        log.info("Generating local analysis for {} changed files, {} impacted nodes",
                changedFiles.size(), impactedNodes.size());

        ImpactReport report = new ImpactReport();
        report.setPrNumber("LOCAL");
        report.setPrTitle("Local Impact Analysis");
        report.setConfidenceScore(0.75);

        // Initialize all lists
        report.setImpactedServices(new ArrayList<>());
        report.setImpactedApis(new ArrayList<>());
        report.setImpactedScreens(new ArrayList<>());
        report.setRequiredTestCases(new ArrayList<>());
        report.setRecommendations(new ArrayList<>());

        // Calculate metrics
        int totalAdditions = changedFiles.stream().mapToInt(ChangedFile::getAdditions).sum();
        int totalDeletions = changedFiles.stream().mapToInt(ChangedFile::getDeletions).sum();
        int totalChanges = totalAdditions + totalDeletions;

        // Determine risk level
        int riskLevel = 1;
        String riskDescription = "Low risk - Minor changes detected";

        if (totalChanges > 500) {
            riskLevel = 5;
            riskDescription = "Critical risk - Massive changes detected, requires extensive testing";
        } else if (totalChanges > 200) {
            riskLevel = 4;
            riskDescription = "High risk - Significant changes, thorough review needed";
        } else if (totalChanges > 50) {
            riskLevel = 3;
            riskDescription = "Medium risk - Moderate changes, standard testing required";
        } else if (totalChanges > 10) {
            riskLevel = 2;
            riskDescription = "Low-medium risk - Some impact expected";
        }

        // Adjust risk based on impacted nodes
        long serviceCount = impactedNodes.stream().filter(n -> "SERVICE".equals(n.getType())).count();
        long apiCount = impactedNodes.stream().filter(n -> "API".equals(n.getType())).count();

        if (serviceCount > 5 || apiCount > 10) {
            riskLevel = Math.min(5, riskLevel + 1);
            riskDescription = "High risk - Many services/APIs impacted";
        } else if (serviceCount > 2 || apiCount > 5) {
            riskLevel = Math.min(5, riskLevel + 1);
        }

        ImpactReport.AnalysisSummary summary = new ImpactReport.AnalysisSummary();
        summary.setTotalChanges(changedFiles.size());
        summary.setRiskLevel(riskLevel);
        summary.setRiskDescription(riskDescription);
        summary.setEstimatedTestingHours(riskLevel * 2);
        report.setSummary(summary);

        // Process impacted services
        Map<String, List<DependencyNode>> nodesByType = impactedNodes.stream()
                .collect(Collectors.groupingBy(DependencyNode::getType));

        // Impacted Services
        for (DependencyNode node : nodesByType.getOrDefault("SERVICE", new ArrayList<>())) {
            ImpactReport.ImpactedService service = new ImpactReport.ImpactedService();
            service.setName(node.getName());
            boolean isDirect = changedFiles.stream()
                    .anyMatch(f -> f.getFilename().toLowerCase().contains(node.getName().toLowerCase()));
            service.setImpactType(isDirect ? "DIRECT" : "INDIRECT");
            service.setRisk(riskLevel >= 4 ? "HIGH" : (riskLevel >= 3 ? "MEDIUM" : "LOW"));
            service.setReasons(Arrays.asList(
                    isDirect ? "File directly modified" : "Depends on changed component",
                    "Affects " + (node.getDependents() != null ? node.getDependents().size() : 0) + " downstream components"
            ));
            report.getImpactedServices().add(service);
        }

        // Impacted APIs
        for (DependencyNode node : nodesByType.getOrDefault("API", new ArrayList<>())) {
            ImpactReport.ImpactedAPI api = new ImpactReport.ImpactedAPI();
            api.setEndpoint(node.getPath());
            api.setMethod("REST");
            api.setImpactType("INDIRECT");
            api.setImpactedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE"));
            report.getImpactedApis().add(api);
        }

        // Impacted UI Screens
        for (DependencyNode node : nodesByType.getOrDefault("UI_COMPONENT", new ArrayList<>())) {
            ImpactReport.ImpactedScreen screen = new ImpactReport.ImpactedScreen();
            screen.setName(node.getName());
            screen.setComponent(node.getPath());
            screen.setImpactReason("Depends on changed backend service");
            report.getImpactedScreens().add(screen);
        }

        // Generate test cases based on risk level
        report.getRequiredTestCases().add("✓ Unit tests for changed files (" + changedFiles.size() + " files)");
        report.getRequiredTestCases().add("✓ Integration tests for impacted services (" + report.getImpactedServices().size() + " services)");

        if (!report.getImpactedApis().isEmpty()) {
            report.getRequiredTestCases().add("✓ API contract testing for " + report.getImpactedApis().size() + " endpoints");
        }

        if (riskLevel >= 4) {
            report.getRequiredTestCases().add("✓ Performance/Load testing for critical paths");
            report.getRequiredTestCases().add("✓ Security testing for modified components");
            report.getRequiredTestCases().add("✓ Regression testing for entire affected modules");
        } else if (riskLevel >= 3) {
            report.getRequiredTestCases().add("✓ Regression testing for impacted features");
        }

        if (totalAdditions > 100) {
            report.getRequiredTestCases().add("✓ Code review focusing on new logic");
        }

        if (totalDeletions > 50) {
            report.getRequiredTestCases().add("✓ Verify removed code doesn't break dependencies");
        }

        // Generate recommendations
        report.getRecommendations().add("Run automated tests before merging");
        report.getRecommendations().add("Request peer review for changes");

        if (riskLevel >= 4) {
            report.getRecommendations().add("⚠️  Consider deploying to staging environment first");
            report.getRecommendations().add("⚠️  Coordinate with QA team for thorough testing");
            report.getRecommendations().add("⚠️  Prepare rollback plan before production deployment");
        } else if (riskLevel >= 3) {
            report.getRecommendations().add("Deploy during low-traffic hours");
            report.getRecommendations().add("Monitor metrics after deployment");
        }

        if (report.getImpactedServices().stream().anyMatch(s -> "DIRECT".equals(s.getImpactType()))) {
            report.getRecommendations().add("Update service documentation if interfaces changed");
        }

        if (!report.getImpactedApis().isEmpty()) {
            report.getRecommendations().add("Verify API versioning and backward compatibility");
        }

        return report;
    }

    private ImpactReport parseAIResponse(String aiResponse,
                                         List<ChangedFile> changedFiles,
                                         List<DependencyNode> impactedNodes) {
        try {
            // Try to extract JSON from response
            int startJson = aiResponse.indexOf("{");
            int endJson = aiResponse.lastIndexOf("}") + 1;
            if (startJson >= 0 && endJson > startJson) {
                String jsonStr = aiResponse.substring(startJson, endJson);
                ImpactReport report = objectMapper.readValue(jsonStr, ImpactReport.class);

                // Ensure lists are not null
                if (report.getImpactedServices() == null) report.setImpactedServices(new ArrayList<>());
                if (report.getImpactedApis() == null) report.setImpactedApis(new ArrayList<>());
                if (report.getImpactedScreens() == null) report.setImpactedScreens(new ArrayList<>());
                if (report.getRequiredTestCases() == null) report.setRequiredTestCases(new ArrayList<>());
                if (report.getRecommendations() == null) report.setRecommendations(new ArrayList<>());

                report.setConfidenceScore(0.85);
                return report;
            }
        } catch (Exception e) {
            log.warn("Failed to parse AI response, using local analysis", e);
        }

        return generateLocalAnalysis(changedFiles, impactedNodes);
    }
}*/
