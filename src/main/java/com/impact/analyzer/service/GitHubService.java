package com.impact.analyzer.service;
import com.impact.analyzer.model.ImpactReport;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class GitHubService {

    @Value("${github.token}")
    private String githubToken;

    private GitHub github;


    public List<String> getChangedFiles(String repoFullName, int prNumber) throws IOException {
        List<String> changedFiles = new ArrayList<>();

        try {
            GHRepository repository = github.getRepository(repoFullName);
            GHPullRequest pullRequest = repository.getPullRequest(prNumber);
            List<GHPullRequestFileDetail> files = pullRequest.listFiles().toList();

            log.info("Retrieved {} changed files from GitHub API for PR #{}", files.size(), prNumber);

            // Extract file paths
            for (GHPullRequestFileDetail file : files) {
                String filename = file.getFilename();
                // Filter to only source code files we care about
                if (isSourceCodeFile(filename)) {
                    changedFiles.add(filename);
                    log.debug("Changed file: {} (status: {})", filename, file.getStatus());
                }
            }

            // Also capture the diff content if needed for deeper analysis
            for (GHPullRequestFileDetail file : files) {
                if (isSourceCodeFile(file.getFilename())) {
                    log.debug("File {} has {} additions and {} deletions",
                            file.getFilename(), file.getAdditions(), file.getDeletions());

                    // You can also access the patch/diff content
                    String patch = file.getPatch();
                    if (patch != null && !patch.isEmpty()) {
                        log.debug("Patch preview: {}", patch.substring(0, Math.min(200, patch.length())));
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