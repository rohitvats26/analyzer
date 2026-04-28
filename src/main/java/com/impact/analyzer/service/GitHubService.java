package com.impact.analyzer.service;
import com.impact.analyzer.model.ImpactReport;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class GitHubService {

    @Value("${github.token}")
    private String githubToken;

    private GitHub github;

    @PostConstruct
    public void init() {
        try {
            if (githubToken != null && !githubToken.isEmpty()) {
                github = new GitHubBuilder().withOAuthToken(githubToken).build();
                // Test the connection
                github.getRateLimit();
                log.info("GitHub client initialized successfully. Rate limit: {}",
                        github.getRateLimit().getRemaining());
            } else {
                log.warn("GitHub token not configured. PR comments will be disabled.");
            }
        } catch (IOException e) {
            log.error("Failed to initialize GitHub client", e);
        }
    }


    public List<String> getChangedFiles(String repoFullName, int prNumber) throws IOException {
        List<String> changedFiles = new ArrayList<>();

        try {
            if (github == null) {
                log.warn("GitHub client not initialized");
                return changedFiles;
            }

            GHRepository repository = github.getRepository(repoFullName);
            GHPullRequest pullRequest = repository.getPullRequest(prNumber);

            // Get the diff between base and head
            List<GHPullRequestFileDetail> files = pullRequest.listFiles().toList();

            for (GHPullRequestFileDetail file : files) {
                String filename = file.getFilename();
                String status = file.getStatus(); // added, modified, removed, renamed

                if (isSourceCodeFile(filename)) {
                    changedFiles.add(filename);
                    log.debug("Changed file: {} (status: {}, changes: +{}/-{})",
                            filename, status, file.getAdditions(), file.getDeletions());

                    // Get the actual diff/patch for detailed analysis
                    String patch = file.getPatch();
                    if (patch != null) {
                        // Parse patch to understand exactly what changed
                        parsePatchForDetails(filename, patch);
                    }
                }
            }

        } catch (IOException e) {
            log.error("Failed to get changed files from GitHub API for PR #{}", prNumber, e);
            throw e;
        }

        return changedFiles;
    }

    public void postPRComment(String repoName, int prNumber, ImpactReport report) {
        try {
            if (github == null) {
                github = new GitHubBuilder().withOAuthToken(githubToken).build();
            }

            GHRepository repo = github.getRepository(repoName);
            GHPullRequest pr = repo.getPullRequest(prNumber);

            String comment = formatImpactReport(report);
            pr.comment(comment);

            log.info("Posted impact analysis comment to PR #{}", prNumber);

            // Optionally add labels based on risk level
            addRiskLabel(pr, report);


        } catch (IOException e) {
            log.error("Failed to post PR comment", e);
        }
    }

    private void parsePatchForDetails(String filename, String patch) {
        // Parse the unified diff format to understand line-by-line changes
        String[] lines = patch.split("\n");
        int addedLines = 0;
        int removedLines = 0;
        List<Integer> changedLineNumbers = new ArrayList<>();

        for (String line : lines) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                addedLines++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                removedLines++;
            } else if (line.startsWith("@")) {
                // Parse hunk header to get line numbers
                Pattern hunkPattern = Pattern.compile("@@ -(\\d+),?\\d* \\+(\\d+),?\\d* @@");
                Matcher matcher = hunkPattern.matcher(line);
                if (matcher.find()) {
                    changedLineNumbers.add(Integer.parseInt(matcher.group(1)));
                    changedLineNumbers.add(Integer.parseInt(matcher.group(2)));
                }
            }
        }

        log.debug("Patch analysis for {}: +{} -{} lines changed at positions {}",
                filename, addedLines, removedLines, changedLineNumbers);
    }

    private void addRiskLabel(GHPullRequest pr, ImpactReport report) throws IOException {
        int breakingRisk = report.getRiskScores().getOrDefault("BREAKING_CHANGE_RISK", 0);

        if (breakingRisk > 7) {
            pr.addLabels("high-risk", "needs-review");
        } else if (breakingRisk > 3) {
            pr.addLabels("medium-risk");
        } else {
            pr.addLabels("low-risk");
        }
    }

    public void postPRComment(String repoName, int prNumber, String message) {
        if (github == null) {
            log.warn("GitHub client not initialized. Cannot post comment to PR #{}", prNumber);
            return;
        }

        try {
            GHRepository repo = github.getRepository(repoName);
            GHPullRequest pr = repo.getPullRequest(prNumber);
            pr.comment(message);
            log.info("Posted simple comment to PR #{}", prNumber);
        } catch (IOException e) {
            log.error("Failed to post PR comment", e);
        }
    }

    private String formatImpactReport(ImpactReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🚀 AI-Powered Impact Analysis Report\n\n");

        sb.append("### 📊 Impact Summary\n");
        sb.append("| Type | Count |\n");
        sb.append("|------|-------|\n");
        sb.append("| Services | ").append(report.getImpactedServices().size()).append(" |\n");
        sb.append("| APIs | ").append(report.getImpactedApis().size()).append(" |\n");
        sb.append("| UI Components | ").append(report.getImpactedUiComponents().size()).append(" |\n");
        sb.append("| Database Tables | ").append(report.getImpactedDatabaseTables().size()).append(" |\n");
        sb.append("\n");

        if (!report.getImpactedServices().isEmpty()) {
            sb.append("### 🔧 Impacted Services\n");
            for (String service : report.getImpactedServices()) {
                sb.append("- `").append(service).append("`\n");
            }
            sb.append("\n");
        }

        if (!report.getImpactedApis().isEmpty()) {
            sb.append("### 🌐 Impacted APIs\n");
            for (String api : report.getImpactedApis()) {
                sb.append("- `").append(api).append("`\n");
            }
            sb.append("\n");
        }

        if (!report.getImpactedUiComponents().isEmpty()) {
            sb.append("### 🎨 Impacted UI Components\n");
            for (String ui : report.getImpactedUiComponents()) {
                sb.append("- `").append(ui).append("`\n");
            }
            sb.append("\n");
        }

        if (!report.getImpactedDatabaseTables().isEmpty()) {
            sb.append("### 🗄️ Impacted Database Tables\n");
            for (String table : report.getImpactedDatabaseTables()) {
                sb.append("- `").append(table).append("`\n");
            }
            sb.append("\n");
        }

        sb.append("### 🧪 Required Test Cases\n");
        if (report.getRequiredTestCases().isEmpty()) {
            sb.append("✅ No specific test cases required.\n");
        } else {
            for (ImpactReport.TestCase test : report.getRequiredTestCases()) {
                sb.append("- **").append(test.getName()).append("** (")
                        .append(test.getType()).append(", Priority: ").append(test.getPriority())
                        .append(")\n");
            }
        }
        sb.append("\n");

        sb.append("### ⚠️ Risk Assessment\n");
        sb.append("| Risk Factor | Score |\n");
        sb.append("|-------------|-------|\n");
        for (Map.Entry<String, Integer> risk : report.getRiskScores().entrySet()) {
            String riskLevel;
            int score = risk.getValue();
            if (score >= 8) {
                riskLevel = "🔴 HIGH";
            } else if (score >= 5) {
                riskLevel = "🟡 MEDIUM";
            } else {
                riskLevel = "🟢 LOW";
            }
            sb.append("| ").append(risk.getKey()).append(" | ").append(riskLevel).append(" (").append(score).append("/10) |\n");
        }
        sb.append("\n");

        sb.append("**💡 Recommendation:** ");
        int breakingRisk = report.getRiskScores().getOrDefault("BREAKING_CHANGE_RISK", 0);
        if (breakingRisk > 7) {
            sb.append("🚨 **Requires comprehensive testing and code review before merging.**\n");
            sb.append("- Run full regression suite\n");
            sb.append("- Get approval from tech lead\n");
            sb.append("- Consider breaking change communication\n");
        } else if (breakingRisk > 3) {
            sb.append("⚠️ **Recommended to run integration tests before merging.**\n");
            sb.append("- Run affected API tests\n");
            sb.append("- Verify downstream dependencies\n");
        } else {
            sb.append("✅ **Low risk change, unit tests sufficient.**\n");
            sb.append("- Run unit tests for changed components\n");
            sb.append("- Quick sanity check on affected features\n");
        }

        return sb.toString();
    }

    private boolean isSourceCodeFile(String filename) {
        // Define which file extensions we care about
        String[] sourceExtensions = {
                ".java", ".kt", ".scala",           // JVM languages
                ".ts", ".tsx", ".js", ".jsx",       // TypeScript/JavaScript
                ".html", ".vue",                    // Angular/Vue templates
                ".py",                              // Python
                ".go",                              // Go
                ".rb",                              // Ruby
                ".php",                             // PHP
                ".cs",                              // C#
                ".cpp", ".c", ".h",                 // C/C++
                ".xml", ".yaml", ".yml", ".json",   // Config files
                ".sql"                              // SQL files
        };

        for (String ext : sourceExtensions) {
            if (filename.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}