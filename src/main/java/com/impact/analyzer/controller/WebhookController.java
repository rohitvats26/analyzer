package com.impact.analyzer.controller;

import com.impact.analyzer.model.ChangedFile;
import com.impact.analyzer.model.ImpactReport;
import com.impact.analyzer.model.PullRequestEvent;
import com.impact.analyzer.service.GitHubService;
import com.impact.analyzer.service.ImpactAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/webhook")
@Slf4j
public class WebhookController {

    @Autowired
    private GitHubService gitHubService;

    @Autowired
    private ImpactAnalysisService impactAnalysisService;

    @PostMapping("/github")
    public ResponseEntity<String> handleGitHubWebhook(
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestBody PullRequestEvent payload) {

        log.info("Received GitHub event: {}", eventType);

        // Only process pull request events
        if (!"pull_request".equals(eventType)) {
            return ResponseEntity.ok("Ignored non-PR event");
        }

        // Only process opened, synchronize (code push), and reopened events
        String action = payload.getAction();
        if (!List.of("opened", "synchronize", "reopened").contains(action)) {
            log.info("Ignoring PR action: {}", action);
            return ResponseEntity.ok("Ignored action: " + action);
        }

        try {
            // Process the PR asynchronously to avoid timeout
            processPullRequestAsync(payload);
            return ResponseEntity.accepted().body("PR analysis started");

        } catch (Exception e) {
            log.error("Failed to process webhook", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    private void processPullRequestAsync(PullRequestEvent event) {
        try {
            // Fetch changed files
            List<ChangedFile> changedFiles = gitHubService.getChangedFiles(event);

            log.info("Processing PR #{} with {} changed files",
                    event.getPullRequest().getNumber(), changedFiles.size());

            // Run impact analysis
            ImpactReport report = impactAnalysisService.analyzePullRequest(event, changedFiles);

            // Post analysis results as comment
            String comment = formatImpactComment(report);
            String repoFullName = event.getPullRequest().getHead().getRepo().getFull_name();
            gitHubService.postComment(repoFullName, event.getPullRequest().getNumber(), comment);

            // Add label based on risk level
            if (report.getSummary().getRiskLevel() >= 4) {
                gitHubService.addLabel(repoFullName, event.getPullRequest().getNumber(), "high-risk");
            } else if (report.getSummary().getRiskLevel() >= 3) {
                gitHubService.addLabel(repoFullName, event.getPullRequest().getNumber(), "needs-review");
            } else {
                gitHubService.addLabel(repoFullName, event.getPullRequest().getNumber(), "low-risk");
            }

            log.info("Completed analysis for PR #{}", event.getPullRequest().getNumber());

        } catch (Exception e) {
            log.error("Failed to process PR analysis", e);
            try {
                String errorComment = "🚨 **Impact Analysis Failed**\n\n" +
                        "```\n" + e.getMessage() + "\n```\n\n" +
                        "Please check the logs for more details.";
                String repoFullName = event.getPullRequest().getHead().getRepo().getFull_name();
                gitHubService.postComment(repoFullName, event.getPullRequest().getNumber(), errorComment);
            } catch (Exception ex) {
                log.error("Failed to post error comment", ex);
            }
        }
    }

    private String formatImpactComment(ImpactReport report) {
        StringBuilder comment = new StringBuilder();

        comment.append("## 🤖 AI-Powered Impact Analysis\n\n");

        // Risk summary
        int riskLevel = report.getSummary().getRiskLevel();
        String riskEmoji = riskLevel >= 4 ? "🔴" : (riskLevel >= 3 ? "🟡" : "🟢");
        comment.append(String.format("### %s Risk Level: %d/5\n", riskEmoji, riskLevel));
        comment.append(report.getSummary().getRiskDescription()).append("\n\n");

        // Impacted Services
        if (!report.getImpactedServices().isEmpty()) {
            comment.append("### 🎯 Impacted Services\n");
            for (var service : report.getImpactedServices()) {
                String riskIcon = "HIGH".equals(service.getRisk()) ? "🔴" :
                        ("MEDIUM".equals(service.getRisk()) ? "🟡" : "🟢");
                comment.append(String.format("- **%s** %s [%s impact]\n",
                        service.getName(), riskIcon, service.getImpactType()));
                comment.append("  - Reasons: ").append(String.join(", ", service.getReasons())).append("\n");
            }
            comment.append("\n");
        }

        // Impacted APIs
        if (!report.getImpactedApis().isEmpty()) {
            comment.append("### 🔌 Impacted APIs\n");
            for (var api : report.getImpactedApis()) {
                comment.append(String.format("- `%s %s` [%s impact]\n",
                        api.getMethod(), api.getEndpoint(), api.getImpactType()));
                if (!api.getImpactedMethods().isEmpty()) {
                    comment.append("  - Affected methods: ").append(String.join(", ", api.getImpactedMethods())).append("\n");
                }
            }
            comment.append("\n");
        }

        // Test Cases
        comment.append("### 🧪 Required Test Cases\n");
        for (String testCase : report.getRequiredTestCases()) {
            comment.append("- ").append(testCase).append("\n");
        }
        comment.append("\n");

        // Recommendations
        comment.append("### 💡 Recommendations\n");
        for (String recommendation : report.getRecommendations()) {
            comment.append("- ").append(recommendation).append("\n");
        }
        comment.append("\n");

        // Metadata
        comment.append("---\n");
        comment.append(String.format("**Confidence Score:** %.0f%% | ", report.getConfidenceScore() * 100));
        comment.append(String.format("**Estimated Testing Time:** %d hours\n", report.getSummary().getEstimatedTestingHours()));
        comment.append("*Powered by AI & Dependency Graph Analysis*\n");

        return comment.toString();
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "service", "PR Impact Analyzer"
        ));
    }
}