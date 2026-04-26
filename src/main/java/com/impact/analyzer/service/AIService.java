package com.impact.analyzer.service;

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

    @Value("${openai.api.key:}")
    private String openAiKey;

    @Value("${openai.use.fallback:true}")
    private boolean useFallback;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImpactReport analyzeImpact(List<ChangedFile> changedFiles,
                                      List<DependencyNode> impactedNodes) {
        if (openAiKey == null || openAiKey.isEmpty() || useFallback) {
            log.warn("OpenAI API key not configured or fallback enabled, using fallback analysis");
            return generateFallbackAnalysis(changedFiles, impactedNodes);
        }

        // Retry logic with exponential backoff
        int maxRetries = 3;
        int retryDelay = 1000; // Start with 1 second

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String prompt = buildPrompt(changedFiles, impactedNodes);
                String aiResponse = callOpenAI(prompt);
                return parseAIResponse(aiResponse, changedFiles, impactedNodes);
            } catch (Exception e) {
                log.error("AI analysis failed (attempt {}/{}): {}", attempt, maxRetries, e.getMessage());

                if (e.getMessage().contains("429")) {
                    // Rate limit - increase delay and retry
                    retryDelay *= 2; // Exponential backoff
                    log.warn("Rate limited, waiting {}ms before retry", retryDelay);
                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else if (attempt == maxRetries) {
                    log.error("All retries failed, using fallback analysis");
                    return generateFallbackAnalysis(changedFiles, impactedNodes);
                }
            }
        }

        return generateFallbackAnalysis(changedFiles, impactedNodes);
    }

    private String buildPrompt(List<ChangedFile> changedFiles, List<DependencyNode> impactedNodes) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an expert software architect analyzing a Pull Request.\n\n");

        prompt.append("CHANGED FILES:\n");
        for (ChangedFile file : changedFiles) {
            prompt.append(String.format("- %s (%s): +%d -%d\n",
                    file.getFilename(), file.getStatus(), file.getAdditions(), file.getDeletions()));
            if (file.getPatch() != null && file.getPatch().length() < 500) {
                prompt.append("  Patch: ").append(file.getPatch()).append("\n");
            }
        }

        prompt.append("\nIMPACTED COMPONENTS (based on dependency graph):\n");
        for (DependencyNode node : impactedNodes) {
            prompt.append(String.format("- %s (%s): %s\n",
                    node.getName(), node.getType(), node.getPath()));
        }

        prompt.append("\nPlease analyze the impact and provide a JSON response with:\n");
        prompt.append("{\n");
        prompt.append("  \"summary\": {\n");
        prompt.append("    \"riskLevel\": 1-5 (1=low, 5=critical),\n");
        prompt.append("    \"riskDescription\": \"brief description\",\n");
        prompt.append("    \"estimatedTestingHours\": number\n");
        prompt.append("  },\n");
        prompt.append("  \"impactedServices\": [{\"name\": \"\", \"impactType\": \"DIRECT/INDIRECT\", \"risk\": \"HIGH/MEDIUM/LOW\", \"reasons\": []}],\n");
        prompt.append("  \"impactedApis\": [{\"endpoint\": \"\", \"method\": \"\", \"impactType\": \"\", \"impactedMethods\": []}],\n");
        prompt.append("  \"impactedScreens\": [{\"name\": \"\", \"component\": \"\", \"impactReason\": \"\"}],\n");
        prompt.append("  \"requiredTestCases\": [\"test1\", \"test2\"],\n");
        prompt.append("  \"recommendations\": [\"rec1\", \"rec2\"]\n");
        prompt.append("}\n");

        return prompt.toString();
    }

    private String callOpenAI(String prompt) throws Exception {
        URL url = new URL("https://api.openai.com/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + openAiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000); // 30 second timeout
        conn.setReadTimeout(60000);    // 60 second read timeout

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-3.5-turbo");

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.3); // Lower temperature for more consistent results
        requestBody.put("max_tokens", 1000);

        String jsonInput = objectMapper.writeValueAsString(requestBody);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonInput.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            // Read error response for better debugging
            StringBuilder errorResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    errorResponse.append(line);
                }
            }
            throw new RuntimeException("OpenAI API returned " + responseCode + ": " + errorResponse);
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
        }

        Map<String, Object> responseMap = objectMapper.readValue(response.toString(), Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        String content = (String) message.get("content");

        // Extract JSON from the response
        int startJson = content.indexOf("{");
        int endJson = content.lastIndexOf("}") + 1;
        if (startJson >= 0 && endJson > startJson) {
            return content.substring(startJson, endJson);
        }

        return content;
    }

    private ImpactReport parseAIResponse(String aiResponse,
                                         List<ChangedFile> changedFiles,
                                         List<DependencyNode> impactedNodes) {
        try {
            ImpactReport report = objectMapper.readValue(aiResponse, ImpactReport.class);
            report.setPrNumber("PR-" + System.currentTimeMillis());
            report.setPrTitle("Impact Analysis");
            report.setConfidenceScore(0.85);
            report.setAiAnalysis(aiResponse);

            // Ensure lists are never null
            if (report.getImpactedServices() == null) report.setImpactedServices(new ArrayList<>());
            if (report.getImpactedApis() == null) report.setImpactedApis(new ArrayList<>());
            if (report.getImpactedScreens() == null) report.setImpactedScreens(new ArrayList<>());
            if (report.getRequiredTestCases() == null) report.setRequiredTestCases(new ArrayList<>());
            if (report.getRecommendations() == null) report.setRecommendations(new ArrayList<>());

            return report;
        } catch (Exception e) {
            log.error("Failed to parse AI response", e);
            return generateFallbackAnalysis(changedFiles, impactedNodes);
        }
    }

    private ImpactReport generateFallbackAnalysis(List<ChangedFile> changedFiles,
                                                  List<DependencyNode> impactedNodes) {
        ImpactReport report = new ImpactReport();
        report.setPrNumber("FALLBACK");
        report.setPrTitle("Automated Impact Analysis");
        report.setConfidenceScore(0.65);

        // Initialize all lists
        report.setImpactedServices(new ArrayList<>());
        report.setImpactedApis(new ArrayList<>());
        report.setImpactedScreens(new ArrayList<>());
        report.setRequiredTestCases(new ArrayList<>());
        report.setRecommendations(new ArrayList<>());

        // Calculate risk based on changes
        int totalChanges = changedFiles.stream().mapToInt(f -> f.getAdditions() + f.getDeletions()).sum();
        int riskLevel = totalChanges > 500 ? 4 : (totalChanges > 100 ? 3 : 2);

        ImpactReport.AnalysisSummary summary = new ImpactReport.AnalysisSummary();
        summary.setTotalChanges(changedFiles.size());
        summary.setRiskLevel(riskLevel);
        summary.setRiskDescription(riskLevel > 3 ? "High risk - Major changes detected" : "Moderate risk - Review recommended");
        summary.setEstimatedTestingHours(riskLevel * 2);
        report.setSummary(summary);

        // Identify impacted services
        for (DependencyNode node : impactedNodes) {
            if ("SERVICE".equals(node.getType())) {
                ImpactReport.ImpactedService service = new ImpactReport.ImpactedService();
                service.setName(node.getName());
                service.setImpactType(changedFiles.stream().anyMatch(f -> f.getFilename().contains(node.getName())) ? "DIRECT" : "INDIRECT");
                service.setRisk(riskLevel > 3 ? "HIGH" : "MEDIUM");
                service.setReasons(Arrays.asList("File changed in dependency chain"));
                report.getImpactedServices().add(service);
            }
        }

        // Identify impacted APIs
        for (DependencyNode node : impactedNodes) {
            if ("API".equals(node.getType())) {
                ImpactReport.ImpactedAPI api = new ImpactReport.ImpactedAPI();
                api.setEndpoint(node.getPath());
                api.setMethod("REST");
                api.setImpactType("INDIRECT");
                api.setImpactedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE"));
                report.getImpactedApis().add(api);
            }
        }

        // Generate test cases
        report.getRequiredTestCases().add("Unit testing for changed files");
        report.getRequiredTestCases().add("Integration testing for impacted services");
        if (!report.getImpactedApis().isEmpty()) {
            report.getRequiredTestCases().add("API contract testing for " + report.getImpactedApis().size() + " endpoints");
        }
        if (report.getImpactedServices().stream().anyMatch(s -> "HIGH".equals(s.getRisk()))) {
            report.getRequiredTestCases().add("Performance testing for critical paths");
            report.getRequiredTestCases().add("Security testing for changed components");
        }

        // Recommendations
        report.getRecommendations().add("Run comprehensive regression tests");
        report.getRecommendations().add("Review changes with team members");
        report.getRecommendations().add("Update API documentation if interfaces changed");
        if (riskLevel > 3) {
            report.getRecommendations().add("Consider breaking changes and versioning strategy");
            report.getRecommendations().add("Perform staging deployment before production");
        }

        return report;
    }
}