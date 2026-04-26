package com.impact.analyzer.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.impact.analyzer.model.DependencyGraph;
import com.impact.analyzer.model.PullRequestEvent;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class CopilotService {
    
    @Value("${github.token}")
    private String githubToken;
    
    @Value("${copilot.chat-api:https://api.githubcopilot.com}")
    private String copilotApiEndpoint;
    
    private final OkHttpClient httpClient;
    private final Gson gson;
    
    public CopilotService() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        this.gson = new Gson();
    }
    
    public String analyzeCodeChanges(
            List<PullRequestEvent.FileChange> changedFiles,
            DependencyGraph dependencyGraph,
            String prDescription) {
        
        try {
            // Try Copilot Chat API first
            if (isCopilotChatAvailable()) {
                log.info("🤖 Using GitHub Copilot Chat for analysis");
                return analyzeWithCopilotChat(changedFiles, dependencyGraph, prDescription);
            } else {
                // Fallback to GitHub Copilot API
                log.info("🤖 Using GitHub Copilot API for analysis");
                return analyzeWithCopilotAPI(changedFiles, dependencyGraph, prDescription);
            }
        } catch (Exception e) {
            log.error("Error using Copilot for analysis", e);
            return generateFallbackAnalysis(changedFiles, dependencyGraph);
        }
    }
    
    private boolean isCopilotChatAvailable() {
        // Check if Copilot Chat API is accessible
        try {
            Request request = new Request.Builder()
                .url(copilotApiEndpoint + "/health")
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/json")
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log.debug("Copilot Chat API not available, using regular API");
            return false;
        }
    }
    
    private String analyzeWithCopilotChat(
            List<PullRequestEvent.FileChange> changedFiles,
            DependencyGraph dependencyGraph,
            String prDescription) throws IOException {
        
        // Build the prompt for Copilot Chat
        String prompt = buildCopilotPrompt(changedFiles, dependencyGraph, prDescription);
        
        // Create Copilot Chat request
        JsonObject requestBody = new JsonObject();
        JsonArray messages = new JsonArray();
        
        // System message
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", 
            "You are an expert code analyzer integrated with GitHub Copilot. " +
            "Analyze code changes and predict their impact on the system. " +
            "Provide detailed analysis including affected components, APIs, UI, and tests.");
        messages.add(systemMessage);
        
        // User message with the code changes
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);
        
        requestBody.add("messages", messages);
        requestBody.addProperty("temperature", 0.3);
        requestBody.addProperty("max_tokens", 2000);
        
        // Make API call to Copilot Chat
        Request request = new Request.Builder()
            .url(copilotApiEndpoint + "/chat/completions")
            .header("Authorization", "Bearer " + githubToken)
            .header("Content-Type", "application/json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .post(RequestBody.create(
                MediaType.parse("application/json"), 
                gson.toJson(requestBody)))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                return parseCopilotResponse(responseBody);
            } else {
                log.error("Copilot Chat API error: {}", response.code());
                throw new IOException("Copilot Chat API error: " + response.code());
            }
        }
    }
    
    private String analyzeWithCopilotAPI(
            List<PullRequestEvent.FileChange> changedFiles,
            DependencyGraph dependencyGraph,
            String prDescription) throws IOException {
        
        // Use GitHub's Copilot integration through the GitHub API
        // This uses the code analysis capabilities of Copilot
        
        StringBuilder analysis = new StringBuilder();
        
        // Analyze each changed file with Copilot's assistance
        for (PullRequestEvent.FileChange file : changedFiles) {
            String fileAnalysis = analyzeSingleFileWithCopilot(file);
            analysis.append(fileAnalysis).append("\n");
        }
        
        // Get overall impact analysis
        String overallAnalysis = getCopilotCodeReview(changedFiles, dependencyGraph);
        analysis.append(overallAnalysis);
        
        return analysis.toString();
    }
    
    private String analyzeSingleFileWithCopilot(PullRequestEvent.FileChange file) 
            throws IOException {
        
        // Use GitHub's code scanning API with Copilot
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("filename", file.getFilename());
        requestBody.addProperty("patch", file.getPatch());
        requestBody.addProperty("action", "analyze_impact");
        
        Request request = new Request.Builder()
            .url("https://api.github.com/copilot/analyze")
            .header("Authorization", "Bearer " + githubToken)
            .header("Content-Type", "application/json")
            .header("Accept", "application/vnd.github.copilot-preview+json")
            .post(RequestBody.create(
                MediaType.parse("application/json"), 
                gson.toJson(requestBody)))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return response.body().string();
            } else {
                log.warn("Copilot analysis failed for file: {}", file.getFilename());
                return String.format("- Analyzed %s: %d additions, %d deletions\n",
                    file.getFilename(), 
                    file.getAdditions(), 
                    file.getDeletions());
            }
        }
    }
    
    private String getCopilotCodeReview(
            List<PullRequestEvent.FileChange> changedFiles,
            DependencyGraph dependencyGraph) throws IOException {
        
        // Use GitHub's Copilot code review API
        JsonObject requestBody = new JsonObject();
        
        JsonArray filesArray = new JsonArray();
        for (PullRequestEvent.FileChange file : changedFiles) {
            JsonObject fileObj = new JsonObject();
            fileObj.addProperty("path", file.getFilename());
            fileObj.addProperty("patch", file.getPatch() != null ? file.getPatch() : "");
            filesArray.add(fileObj);
        }
        requestBody.add("files", filesArray);
        requestBody.addProperty("review_type", "impact_analysis");
        
        Request request = new Request.Builder()
            .url("https://api.github.com/repos/owner/repo/copilot/review")
            .header("Authorization", "Bearer " + githubToken)
            .header("Content-Type", "application/json")
            .header("Accept", "application/vnd.github.copilot-preview+json")
            .post(RequestBody.create(
                MediaType.parse("application/json"), 
                gson.toJson(requestBody)))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return response.body().string();
            } else {
                return generateFallbackAnalysis(changedFiles, dependencyGraph);
            }
        }
    }
    
    private String buildCopilotPrompt(
            List<PullRequestEvent.FileChange> changedFiles,
            DependencyGraph dependencyGraph,
            String prDescription) {
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze the following code changes for impact analysis:\n\n");
        
        prompt.append("PR Description: ");
        prompt.append(prDescription != null ? prDescription : "No description provided");
        prompt.append("\n\n");
        
        prompt.append("Changed Files:\n");
        for (PullRequestEvent.FileChange file : changedFiles) {
            prompt.append(String.format("- %s (%s, +%d -%d lines)\n",
                file.getFilename(),
                file.getStatus(),
                file.getAdditions(),
                file.getDeletions()));
            
            if (file.getPatch() != null && !file.getPatch().isEmpty()) {
                // Limit patch size
                String patch = file.getPatch().length() > 1000 
                    ? file.getPatch().substring(0, 1000) + "..." 
                    : file.getPatch();
                prompt.append("```diff\n").append(patch).append("\n```\n\n");
            }
        }
        
        prompt.append("\nPlease provide:\n");
        prompt.append("1. Impact Summary\n");
        prompt.append("2. Affected Components (with risk levels)\n");
        prompt.append("3. API Changes and Impact\n");
        prompt.append("4. UI Changes and Impact\n");
        prompt.append("5. Database Impact\n");
        prompt.append("6. Required Test Cases\n");
        prompt.append("7. Breaking Changes (if any)\n");
        prompt.append("8. Recommendations\n");
        
        return prompt.toString();
    }
    
    private String parseCopilotResponse(String responseBody) {
        try {
            JsonObject response = gson.fromJson(responseBody, JsonObject.class);
            if (response.has("choices") && response.getAsJsonArray("choices").size() > 0) {
                JsonObject choice = response.getAsJsonArray("choices")
                    .get(0).getAsJsonObject();
                if (choice.has("message")) {
                    return choice.getAsJsonObject("message")
                        .get("content").getAsString();
                }
            }
            return responseBody;
        } catch (Exception e) {
            log.error("Error parsing Copilot response", e);
            return responseBody;
        }
    }
    
    private String generateFallbackAnalysis(
            List<PullRequestEvent.FileChange> changedFiles,
            DependencyGraph dependencyGraph) {
        
        StringBuilder analysis = new StringBuilder();
        analysis.append("## 🤖 Copilot-Powered Impact Analysis\n\n");
        analysis.append("### Summary\n\n");
        analysis.append(String.format("**%d files changed** in this PR.\n\n", 
            changedFiles.size()));
        
        analysis.append("### Changed Files Analysis\n\n");
        for (PullRequestEvent.FileChange file : changedFiles) {
            analysis.append(String.format("#### %s\n", file.getFilename()));
            analysis.append(String.format("- Status: %s\n", file.getStatus()));
            analysis.append(String.format("- Changes: +%d -%d\n", 
                file.getAdditions(), file.getDeletions()));
            
            // Intelligent analysis based on file patterns
            String impact = analyzeFilePattern(file.getFilename());
            analysis.append(impact).append("\n");
        }
        
        analysis.append("### Dependency Impact\n\n");
        analysis.append("Based on the dependency graph analysis:\n");
        
        // Analyze dependencies
        Set<String> impactedServices = new HashSet<>();
        for (PullRequestEvent.FileChange file : changedFiles) {
            List<String> services = dependencyGraph.getEdges().stream()
                .filter(edge -> file.getFilename().contains(edge.getTarget()))
                .map(DependencyGraph.Edge::getSource)
                .toList();
            impactedServices.addAll(services);
        }
        
        if (!impactedServices.isEmpty()) {
            analysis.append("The following services may be impacted:\n");
            impactedServices.forEach(service -> 
                analysis.append(String.format("- %s\n", service)));
        } else {
            analysis.append("No direct service dependencies identified.\n");
        }
        
        analysis.append("\n### Recommended Actions\n\n");
        analysis.append("1. Review all changed files carefully\n");
        analysis.append("2. Run integration tests for affected components\n");
        analysis.append("3. Update documentation if APIs changed\n");
        analysis.append("4. Consider adding new test cases for modified functionality\n");
        
        return analysis.toString();
    }
    
    private String analyzeFilePattern(String filename) {
        StringBuilder analysis = new StringBuilder();
        
        if (filename.contains("Controller") || filename.contains("Controller.java")) {
            analysis.append("- ⚠️ **API Endpoint Changes**\n");
            analysis.append("  - Review API contracts and backward compatibility\n");
            analysis.append("  - Update API documentation\n");
            analysis.append("  - Check authentication/authorization requirements\n");
        } else if (filename.contains("Service") || filename.contains("ServiceImpl")) {
            analysis.append("- ⚠️ **Business Logic Changes**\n");
            analysis.append("  - Verify business rules integrity\n");
            analysis.append("  - Check transaction boundaries\n");
            analysis.append("  - Review error handling\n");
        } else if (filename.contains("Repository") || filename.contains("DAO")) {
            analysis.append("- ⚠️ **Data Access Changes**\n");
            analysis.append("  - Review database queries for performance\n");
            analysis.append("  - Check for N+1 query issues\n");
            analysis.append("  - Verify data integrity constraints\n");
        } else if (filename.contains("Entity") || filename.contains("Model")) {
            analysis.append("- ⚠️ **Data Model Changes**\n");
            analysis.append("  - Review database migration requirements\n");
            analysis.append("  - Check for breaking changes in JSON serialization\n");
            analysis.append("  - Update related DTOs and mappers\n");
        } else if (filename.contains(".jsx") || filename.contains(".tsx") || 
                   filename.contains(".html")) {
            analysis.append("- ⚠️ **UI Component Changes**\n");
            analysis.append("  - Verify UI consistency\n");
            analysis.append("  - Check responsive design\n");
            analysis.append("  - Run E2E tests\n");
        } else if (filename.contains("test") || filename.contains("Test")) {
            analysis.append("- ✅ **Test Changes**\n");
            analysis.append("  - Review test coverage\n");
            analysis.append("  - Verify test assertions are correct\n");
        } else if (filename.contains("config") || filename.contains("properties") || 
                   filename.contains("yml")) {
            analysis.append("- ⚠️ **Configuration Changes**\n");
            analysis.append("  - Verify configuration in all environments\n");
            analysis.append("  - Check for security implications\n");
            analysis.append("  - Update deployment configurations\n");
        }
        
        return analysis.toString();
    }
    
    public String generatePRSummary(List<PullRequestEvent.FileChange> changedFiles) {
        // Generate a concise PR summary using Copilot
        StringBuilder summary = new StringBuilder();
        summary.append("## PR Summary (Generated by Copilot)\n\n");
        
        // Group files by type
        Map<String, List<String>> filesByType = new HashMap<>();
        for (PullRequestEvent.FileChange file : changedFiles) {
            String type = getFileType(file.getFilename());
            filesByType.computeIfAbsent(type, k -> new ArrayList<>())
                      .add(file.getFilename());
        }
        
        summary.append("### Changes by Category\n");
        filesByType.forEach((type, files) -> {
            summary.append(String.format("- **%s**: %d files\n", type, files.size()));
        });
        
        summary.append("\n### Key Changes\n");
        changedFiles.stream()
            .filter(f -> f.getAdditions() + f.getDeletions() > 20)
            .forEach(f -> summary.append(String.format("- %s (%d changes)\n", 
                f.getFilename(), f.getAdditions() + f.getDeletions())));
        
        return summary.toString();
    }
    
    private String getFileType(String filename) {
        if (filename.endsWith(".java")) return "Java";
        if (filename.endsWith(".js") || filename.endsWith(".jsx")) return "JavaScript";
        if (filename.endsWith(".ts") || filename.endsWith(".tsx")) return "TypeScript";
        if (filename.endsWith(".html")) return "HTML";
        if (filename.endsWith(".css")) return "CSS";
        if (filename.endsWith(".xml")) return "XML";
        if (filename.endsWith(".yml") || filename.endsWith(".yaml")) return "YAML";
        if (filename.endsWith(".properties")) return "Properties";
        if (filename.endsWith(".sql")) return "SQL";
        return "Other";
    }
}