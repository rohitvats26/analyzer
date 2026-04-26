package com.impact.analyzer.controller;

import com.impact.analyzer.config.WebhookSecurity;
import com.impact.analyzer.model.ChangedFile;
import com.impact.analyzer.model.ImpactReport;
import com.impact.analyzer.model.PullRequestEvent;
import com.impact.analyzer.service.GitHubService;
import com.impact.analyzer.service.ImpactAnalysisService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/webhook")
@Slf4j
public class WebhookController {

    @Autowired
    private GitHubService gitHubService;

    @Autowired
    private WebhookSecurity webhookSecurity;

    @Autowired
    private ImpactAnalysisService impactAnalysisService;

    @Value("${github.token:}")
    private String githubToken;

    @PostMapping("/github")
    public ResponseEntity<String> handleGitHubWebhook(
            HttpServletRequest request,
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload) {

        log.info("Received GitHub event: {}", eventType);

        // Verify webhook signature
        if (!webhookSecurity.verifySignature(payload, signature)) {
            log.error("Invalid webhook signature - rejecting request");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Invalid webhook signature");
        }

        // Only process pull request events
        if (!"pull_request".equals(eventType)) {
            return ResponseEntity.ok("Ignored non-PR event");
        }

        // Parse the payload
        PullRequestEvent event;
        try {
            event = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(payload, PullRequestEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse webhook payload", e);
            return ResponseEntity.badRequest().body("Invalid payload");
        }

        // Only process opened, synchronize (code push), and reopened events
        String action = event.getAction();
        if (!List.of("opened", "synchronize", "reopened").contains(action)) {
            log.info("Ignoring PR action: {}", action);
            return ResponseEntity.ok("Ignored action: " + action);
        }

        // Check for GitHub token before processing
        if (githubToken == null || githubToken.isEmpty()) {
            log.error("GitHub token not configured. Please set GITHUB_TOKEN environment variable.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("GitHub token not configured");
        }

        try {
            // Process the PR asynchronously to avoid timeout
            CompletableFuture.runAsync(() -> processPullRequestAsync(event));
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
            String repoFullName = event.getPullRequest().getHead().getRepo().getFullName();
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
                String repoFullName = event.getPullRequest().getHead().getRepo().getFullName();
                gitHubService.postComment(repoFullName, event.getPullRequest().getNumber(), errorComment);
            } catch (Exception ex) {
                log.error("Failed to post error comment", ex);
            }
        }
    }

    private String formatImpactComment(ImpactReport report) {
        StringBuilder comment = new StringBuilder();

        comment.append("## 🤖 AI-Powered Impact Analysis\n\n");

        // Risk summary with null checks
        if (report.getSummary() != null) {
            int riskLevel = report.getSummary().getRiskLevel();
            String riskEmoji = riskLevel >= 4 ? "🔴" : (riskLevel >= 3 ? "🟡" : "🟢");
            comment.append(String.format("### %s Risk Level: %d/5\n", riskEmoji, riskLevel));
            comment.append(report.getSummary().getRiskDescription() != null ?
                    report.getSummary().getRiskDescription() : "No description available").append("\n\n");
        }

        // Impacted Services - with null check
        if (report.getImpactedServices() != null && !report.getImpactedServices().isEmpty()) {
            comment.append("### 🎯 Impacted Services\n");
            for (var service : report.getImpactedServices()) {
                if (service != null) {
                    String riskIcon = "HIGH".equals(service.getRisk()) ? "🔴" :
                            ("MEDIUM".equals(service.getRisk()) ? "🟡" : "🟢");
                    comment.append(String.format("- **%s** %s [%s impact]\n",
                            service.getName() != null ? service.getName() : "Unknown",
                            riskIcon,
                            service.getImpactType() != null ? service.getImpactType() : "Unknown"));
                    if (service.getReasons() != null && !service.getReasons().isEmpty()) {
                        comment.append("  - Reasons: ").append(String.join(", ", service.getReasons())).append("\n");
                    }
                }
            }
            comment.append("\n");
        }

        // Impacted APIs - with null check
        if (report.getImpactedApis() != null && !report.getImpactedApis().isEmpty()) {
            comment.append("### 🔌 Impacted APIs\n");
            for (var api : report.getImpactedApis()) {
                if (api != null) {
                    comment.append(String.format("- `%s %s` [%s impact]\n",
                            api.getMethod() != null ? api.getMethod() : "REST",
                            api.getEndpoint() != null ? api.getEndpoint() : "Unknown",
                            api.getImpactType() != null ? api.getImpactType() : "INDIRECT"));
                    if (api.getImpactedMethods() != null && !api.getImpactedMethods().isEmpty()) {
                        comment.append("  - Affected methods: ").append(String.join(", ", api.getImpactedMethods())).append("\n");
                    }
                }
            }
            comment.append("\n");
        }

        // Impacted Screens - with null check
        if (report.getImpactedScreens() != null && !report.getImpactedScreens().isEmpty()) {
            comment.append("### 🖥️ Impacted UI Screens\n");
            for (var screen : report.getImpactedScreens()) {
                if (screen != null) {
                    comment.append(String.format("- **%s** (%s)\n",
                            screen.getName() != null ? screen.getName() : "Unknown",
                            screen.getComponent() != null ? screen.getComponent() : "Unknown"));
                    if (screen.getImpactReason() != null) {
                        comment.append("  - Reason: ").append(screen.getImpactReason()).append("\n");
                    }
                }
            }
            comment.append("\n");
        }

        // Test Cases - with null check
        if (report.getRequiredTestCases() != null && !report.getRequiredTestCases().isEmpty()) {
            comment.append("### 🧪 Required Test Cases\n");
            for (String testCase : report.getRequiredTestCases()) {
                if (testCase != null) {
                    comment.append("- ").append(testCase).append("\n");
                }
            }
            comment.append("\n");
        }

        // Recommendations - with null check
        if (report.getRecommendations() != null && !report.getRecommendations().isEmpty()) {
            comment.append("### 💡 Recommendations\n");
            for (String recommendation : report.getRecommendations()) {
                if (recommendation != null) {
                    comment.append("- ").append(recommendation).append("\n");
                }
            }
            comment.append("\n");
        }

        // Metadata
        comment.append("---\n");
        comment.append(String.format("**Confidence Score:** %.0f%% | ", report.getConfidenceScore() * 100));
        if (report.getSummary() != null) {
            comment.append(String.format("**Estimated Testing Time:** %d hours\n", report.getSummary().getEstimatedTestingHours()));
        }
        comment.append("*Powered by AI & Dependency Graph Analysis*\n");

        return comment.toString();
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "service", "PR Impact Analyzer",
                "webhook-secure", String.valueOf(
                        !webhookSecurity.getClass().getDeclaredFields()[0].toString().isEmpty()
                )
        ));
    }
}