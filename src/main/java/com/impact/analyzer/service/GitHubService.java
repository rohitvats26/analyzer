package com.impact.analyzer.service;
import com.impact.analyzer.model.ImpactReport;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.Map;

@Service
@Slf4j
public class GitHubService {

    @Value("${github.token}")
    private String githubToken;

    private GitHub github;

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

        sb.append("### 🧪 Required Test Cases\n");
        for (ImpactReport.TestCase test : report.getRequiredTestCases()) {
            sb.append("- **").append(test.getName()).append("** (")
                    .append(test.getType()).append(", Priority: ").append(test.getPriority())
                    .append(")\n");
        }
        sb.append("\n");

        sb.append("### ⚠️ Risk Assessment\n");
        sb.append("| Risk Factor | Score |\n");
        sb.append("|-------------|-------|\n");
        for (Map.Entry<String, Integer> risk : report.getRiskScores().entrySet()) {
            sb.append("| ").append(risk.getKey()).append(" | ").append(risk.getValue()).append("/10 |\n");
        }

        sb.append("\n**💡 Recommendation:** ");
        if (report.getRiskScores().getOrDefault("BREAKING_CHANGE_RISK", 0) > 7) {
            sb.append("Requires comprehensive testing and code review before merging.\n");
        } else if (report.getRiskScores().getOrDefault("BREAKING_CHANGE_RISK", 0) > 3) {
            sb.append("Recommended to run integration tests before merging.\n");
        } else {
            sb.append("Low risk change, unit tests sufficient.\n");
        }

        return sb.toString();
    }
}