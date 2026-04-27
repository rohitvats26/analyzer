package com.impact.analyzer.controller;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.impact.analyzer.model.DependencyGraph;
import com.impact.analyzer.model.ImpactReport;
import com.impact.analyzer.service.DependencyAnalyzer;
import com.impact.analyzer.service.GitHubService;
import com.impact.analyzer.service.ImpactAnalyzer;
import com.impact.analyzer.service.OpenTelemetryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/webhook")
@Slf4j
public class WebhookController {

    private final DependencyAnalyzer dependencyAnalyzer;
    private final ImpactAnalyzer impactAnalyzer;
    private final GitHubService gitHubService;
    private final OpenTelemetryService otelService;

    @Value("${analyzer.workspace:/tmp/pr-analyzer}")
    private String workspacePath;

    public WebhookController(DependencyAnalyzer dependencyAnalyzer, ImpactAnalyzer impactAnalyzer, GitHubService gitHubService, OpenTelemetryService otelService) {
        this.dependencyAnalyzer = dependencyAnalyzer;
        this.impactAnalyzer = impactAnalyzer;
        this.gitHubService = gitHubService;
        this.otelService = otelService;
    }

    @PostMapping("/github")
    public String handlePRWebhook(@RequestBody String payload,
                                  @RequestHeader("X-GitHub-Event") String eventType) {
        log.info("Received GitHub webhook event: {}", eventType);
        log.info("Payload: {}", payload);

        if (!"pull_request".equals(eventType)) {
            return "Ignored event: " + eventType;
        }

        try {
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();

            String action = json.get("action").getAsString();
            if (!"opened".equals(action) && !"synchronize".equals(action)) {
                return "Ignored action: " + action;
            }

            JsonObject pr = json.getAsJsonObject("pull_request");
            String repoFullName = json.getAsJsonObject("repository").get("full_name").getAsString();
            int prNumber = pr.get("number").getAsInt();
            String headSha = pr.getAsJsonObject("head").get("sha").getAsString();
            String cloneUrl = pr.getAsJsonObject("head").getAsJsonObject("repo").get("clone_url").getAsString();
            String branch = pr.getAsJsonObject("head").get("ref").getAsString();

            // Get changed files
            List<String> changedFiles = getChangedFiles(cloneUrl, branch, headSha);
            log.info("PR #{} changed {} files", prNumber, changedFiles.size());

            // Analyze repository and build dependency graph
            DependencyGraph graph = dependencyAnalyzer.analyzeRepository(
                    cloneUrl, branch, workspacePath + "/" + System.currentTimeMillis()
            );

            // Analyze impact using reverse traversal
            ImpactReport report = impactAnalyzer.analyzeImpact(graph, changedFiles);

            // Capture runtime dependencies (simulated)
            captureRuntimeDependencies(graph, report);

            // Post comment to PR
            gitHubService.postPRComment(repoFullName, prNumber, report);

            log.info("Completed impact analysis for PR #{}", prNumber);
            return "Analysis completed";

        } catch (Exception e) {
            log.error("Failed to process webhook", e);
            return "Error: " + e.getMessage();
        }
    }

    private List<String> getChangedFiles(String repoUrl, String branch, String commitSha) {
        // In production, use GitHub API to get files changed in PR
        // For demo, return sample files
        return Arrays.asList(
                "src/main/java/com/example/UserService.java",
                "src/main/java/com/example/UserController.java"
        );
    }

    private void captureRuntimeDependencies(DependencyGraph graph, ImpactReport report) {
        // Simulate capturing runtime dependencies from OpenTelemetry
        for (String service : report.getImpactedServices()) {
            Set<String> deps = graph.getForwardDependencies(service);
            for (String dep : deps) {
                otelService.captureDependency(service, dep, "call");
            }
        }

        log.info("Captured {} runtime dependencies",
                otelService.getRuntimeDependencies().size());
    }
}