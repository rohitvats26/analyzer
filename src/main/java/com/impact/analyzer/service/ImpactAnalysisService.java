package com.impact.analyzer.service;

import com.impact.analyzer.model.DependencyGraph;
import com.impact.analyzer.model.ImpactReport;
import com.impact.analyzer.model.PullRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImpactAnalysisService {

    private final GitHubService gitHubService;
    private final DependencyGraphService dependencyGraphService;
    private final CopilotService copilotService; // Changed from AIService

    @Async
    public CompletableFuture<Void> analyzePullRequest(PullRequestEvent event) {
        try {
            log.info("🔍 Starting Copilot-powered impact analysis for PR #{}",
                    event.getNumber());

            // Step 1: Get changed files from PR
            List<PullRequestEvent.FileChange> changedFiles =
                    gitHubService.getPRFiles(event);
            log.info("📁 Found {} changed files", changedFiles.size());

            if (changedFiles.isEmpty()) {
                log.warn("No files changed in this PR");
                postEmptyAnalysis(event);
                return CompletableFuture.completedFuture(null);
            }

            // Step 2: Build/Load dependency graph
            DependencyGraph dependencyGraph =
                    dependencyGraphService.buildDependencyGraph(event.getRepository());

            // Step 3: Identify impacted components
            Set<ImpactReport.ImpactedComponent> impactedComponents =
                    identifyImpactedComponents(changedFiles, dependencyGraph);

            // Step 4: Use Copilot for analysis
            String copilotAnalysis = copilotService.analyzeCodeChanges(
                    changedFiles,
                    dependencyGraph,
                    event.getPullRequest().getBody());

            // Step 5: Generate PR summary using Copilot
            String prSummary = copilotService.generatePRSummary(changedFiles);

            // Step 6: Generate complete impact report
            ImpactReport report = generateImpactReport(
                    event,
                    impactedComponents,
                    copilotAnalysis,
                    dependencyGraph,
                    prSummary);

            // Step 7: Post analysis as PR comment
            gitHubService.postAnalysisComment(
                    event.getRepository().getFullName(),
                    event.getNumber(),
                    formatReportForPR(report));

            // Step 8: Add labels based on analysis
            addImpactLabels(event, report);

            log.info("✅ Copilot impact analysis completed for PR #{}",
                    event.getNumber());

            return CompletableFuture.completedFuture(null);

        } catch (Exception e) {
            log.error("❌ Error during impact analysis for PR #{}",
                    event.getNumber(), e);

            try {
                gitHubService.postAnalysisComment(
                        event.getRepository().getFullName(),
                        event.getNumber(),
                        "⚠️ Error during Copilot impact analysis: " + e.getMessage());
            } catch (Exception ex) {
                log.error("Failed to post error comment", ex);
            }

            return CompletableFuture.failedFuture(e);
        }
    }

    private Set<ImpactReport.ImpactedComponent> identifyImpactedComponents(
            List<PullRequestEvent.FileChange> changedFiles,
            DependencyGraph dependencyGraph) {

        Set<ImpactReport.ImpactedComponent> impacted = new HashSet<>();

        for (PullRequestEvent.FileChange file : changedFiles) {
            String filename = file.getFilename();

            // Determine component type from file path
            String componentType = determineComponentType(filename);

            // Find the node in dependency graph
            DependencyGraph.Node node = findNodeByFile(dependencyGraph, filename);

            if (node != null) {
                // Get all dependent components
                List<String> dependents = findDependents(dependencyGraph, node.getId());

                ImpactReport.ImpactedComponent component =
                        new ImpactReport.ImpactedComponent();
                component.setName(extractComponentName(filename));
                component.setType(componentType);
                component.setImpactLevel(determineImpactLevel(file, dependents));
                component.setReason(getChangeReason(file));
                component.setDependents(dependents);

                impacted.add(component);

                // Also add dependents as impacted
                for (String dependentId : dependents) {
                    DependencyGraph.Node depNode = dependencyGraph.getNodes()
                            .get(dependentId);
                    if (depNode != null) {
                        ImpactReport.ImpactedComponent depComponent =
                                new ImpactReport.ImpactedComponent();
                        depComponent.setName(depNode.getName());
                        depComponent.setType(depNode.getType());
                        depComponent.setImpactLevel("MEDIUM");
                        depComponent.setReason("Dependency on changed component: " +
                                component.getName());
                        impacted.add(depComponent);
                    }
                }
            } else {
                // If node not found in graph, still add as impacted component
                ImpactReport.ImpactedComponent component =
                        new ImpactReport.ImpactedComponent();
                component.setName(extractComponentName(filename));
                component.setType(componentType);
                component.setImpactLevel(determineImpactLevel(file, new ArrayList<>()));
                component.setReason(getChangeReason(file));
                component.setDependents(new ArrayList<>());

                impacted.add(component);
            }
        }

        return impacted;
    }

    private String determineComponentType(String filename) {
        if (filename.contains("Controller") || filename.contains("controller")) {
            return "API";
        } else if (filename.contains("Service") || filename.contains("service")) {
            return "SERVICE";
        } else if (filename.contains("Repository") || filename.contains("repository") ||
                filename.contains("DAO") || filename.contains("dao")) {
            return "DB";
        } else if (filename.contains(".html") || filename.contains(".jsx") ||
                filename.contains(".tsx") || filename.contains(".css")) {
            return "UI";
        } else if (filename.contains("Entity") || filename.contains("Model") ||
                filename.contains("entity") || filename.contains("model")) {
            return "MODEL";
        } else if (filename.contains("config") || filename.contains("Config") ||
                filename.contains("properties") || filename.contains("yml")) {
            return "CONFIG";
        }
        return "OTHER";
    }

    private String extractComponentName(String filename) {
        // Extract component name from file path
        String[] parts = filename.split("/");
        String name = parts[parts.length - 1];
        return name.replace(".java", "")
                .replace(".ts", "")
                .replace(".tsx", "")
                .replace(".js", "")
                .replace(".jsx", "")
                .replace(".html", "")
                .replace(".css", "")
                .replace(".xml", "")
                .replace(".yml", "")
                .replace(".yaml", "")
                .replace(".properties", "");
    }

    private String determineImpactLevel(
            PullRequestEvent.FileChange file,
            List<String> dependents) {
        int totalChanges = file.getAdditions() + file.getDeletions();

        if (dependents.size() > 5 || totalChanges > 100) {
            return "HIGH";
        } else if (dependents.size() > 2 || totalChanges > 50) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String getChangeReason(PullRequestEvent.FileChange file) {
        if ("added".equals(file.getStatus())) {
            return String.format("New file added (%d lines)",
                    file.getAdditions());
        } else if ("removed".equals(file.getStatus())) {
            return String.format("File removed (%d lines)",
                    file.getDeletions());
        } else if ("renamed".equals(file.getStatus())) {
            return "File renamed";
        } else {
            return String.format("Modified (+%d -%d lines)",
                    file.getAdditions(), file.getDeletions());
        }
    }

    private DependencyGraph.Node findNodeByFile(
            DependencyGraph graph, String filename) {
        if (graph == null || graph.getNodes() == null) {
            return null;
        }

        return graph.getNodes().values().stream()
                .filter(node -> node.getMetadata() != null &&
                        node.getMetadata().containsKey("file") &&
                        filename.contains((String) node.getMetadata().get("file")))
                .findFirst()
                .orElse(null);
    }

    private List<String> findDependents(DependencyGraph graph, String nodeId) {
        List<String> dependents = new ArrayList<>();
        if (graph == null || graph.getEdges() == null) {
            return dependents;
        }

        for (DependencyGraph.Edge edge : graph.getEdges()) {
            if (edge.getTarget().equals(nodeId)) {
                dependents.add(edge.getSource());
            }
        }
        return dependents;
    }

    private ImpactReport generateImpactReport(
            PullRequestEvent event,
            Set<ImpactReport.ImpactedComponent> impactedComponents,
            String analysis,
            DependencyGraph dependencyGraph) {

        ImpactReport report = new ImpactReport();
        report.setPrNumber(String.valueOf(event.getNumber()));
        report.setSummary(analysis);  // Using the analysis directly as summary
        report.setImpactedComponents(new ArrayList<>(impactedComponents));

        // Extract API and UI impacts
        List<String> affectedAPIs = impactedComponents.stream()
                .filter(c -> "API".equals(c.getType()))
                .map(ImpactReport.ImpactedComponent::getName)
                .collect(Collectors.toList());
        report.setAffectedAPIs(affectedAPIs);

        List<String> affectedUIScreens = impactedComponents.stream()
                .filter(c -> "UI".equals(c.getType()))
                .map(ImpactReport.ImpactedComponent::getName)
                .collect(Collectors.toList());
        report.setAffectedUIScreens(affectedUIScreens);

        // Generate test recommendations based on impacted components
        report.setRecommendedTests(generateTestRecommendations(impactedComponents));

        // Calculate overall risk based on impacted components
        report.setRiskLevel(calculateOverallRisk(impactedComponents));

        // Identify affected services
        report.setAffectedServices(identifyAffectedServices(impactedComponents,
                dependencyGraph));

        return report;
    }

    private ImpactReport generateImpactReport(
            PullRequestEvent event,
            Set<ImpactReport.ImpactedComponent> impactedComponents,
            String copilotAnalysis,
            DependencyGraph dependencyGraph,
            String prSummary) {

        ImpactReport report = new ImpactReport();
        report.setPrNumber(String.valueOf(event.getNumber()));

        // Combine PR summary with Copilot analysis
        String fullSummary = prSummary + "\n\n" + copilotAnalysis;
        report.setSummary(fullSummary);

        report.setImpactedComponents(new ArrayList<>(impactedComponents));

        // Extract API and UI impacts
        List<String> affectedAPIs = impactedComponents.stream()
                .filter(c -> "API".equals(c.getType()))
                .map(ImpactReport.ImpactedComponent::getName)
                .collect(Collectors.toList());
        report.setAffectedAPIs(affectedAPIs);

        List<String> affectedUIScreens = impactedComponents.stream()
                .filter(c -> "UI".equals(c.getType()))
                .map(ImpactReport.ImpactedComponent::getName)
                .collect(Collectors.toList());
        report.setAffectedUIScreens(affectedUIScreens);

        // Generate test recommendations
        report.setRecommendedTests(generateTestRecommendations(impactedComponents));

        // Calculate overall risk
        report.setRiskLevel(calculateOverallRisk(impactedComponents));

        // Identify affected services
        report.setAffectedServices(identifyAffectedServices(impactedComponents,
                dependencyGraph));

        return report;
    }

    private List<String> generateTestRecommendations(
            Set<ImpactReport.ImpactedComponent> components) {
        List<String> tests = new ArrayList<>();

        for (ImpactReport.ImpactedComponent component : components) {
            // Add component-specific test recommendations
            tests.add(String.format("Unit test for %s component", component.getName()));

            // Add integration tests for components with dependents
            if (component.getDependents() != null && !component.getDependents().isEmpty()) {
                tests.add(String.format("Integration test for %s with dependents: %s",
                        component.getName(),
                        String.join(", ", component.getDependents().subList(0,
                                Math.min(3, component.getDependents().size())))));
            }

            // Add type-specific test recommendations
            switch (component.getType()) {
                case "API":
                    tests.add(String.format("API endpoint test for %s",
                            component.getName()));
                    tests.add(String.format("API contract test for %s",
                            component.getName()));
                    break;
                case "UI":
                    tests.add(String.format("E2E test for %s screen",
                            component.getName()));
                    tests.add(String.format("Responsive design test for %s",
                            component.getName()));
                    break;
                case "DB":
                    tests.add(String.format("Database migration test for %s",
                            component.getName()));
                    tests.add(String.format("Data integrity test for %s",
                            component.getName()));
                    break;
                case "SERVICE":
                    tests.add(String.format("Service layer test for %s",
                            component.getName()));
                    tests.add(String.format("Business logic validation for %s",
                            component.getName()));
                    break;
            }
        }

        // Remove duplicates while preserving order
        return new ArrayList<>(new LinkedHashSet<>(tests));
    }

    private ImpactReport.RiskLevel calculateOverallRisk(
            Set<ImpactReport.ImpactedComponent> components) {
        if (components == null || components.isEmpty()) {
            return ImpactReport.RiskLevel.LOW;
        }

        long highCount = components.stream()
                .filter(c -> "HIGH".equals(c.getImpactLevel()))
                .count();

        if (highCount > 0) {
            return ImpactReport.RiskLevel.HIGH;
        }

        long mediumCount = components.stream()
                .filter(c -> "MEDIUM".equals(c.getImpactLevel()))
                .count();

        if (mediumCount > 3) {
            return ImpactReport.RiskLevel.HIGH;
        } else if (mediumCount > 0) {
            return ImpactReport.RiskLevel.MEDIUM;
        }

        return ImpactReport.RiskLevel.LOW;
    }

    private List<String> identifyAffectedServices(
            Set<ImpactReport.ImpactedComponent> components,
            DependencyGraph graph) {

        // Add direct service components
        List<String> affectedServices = new ArrayList<>(components.stream()
                .filter(c -> "SERVICE".equals(c.getType()))
                .map(ImpactReport.ImpactedComponent::getName)
                .toList());

        // Add services that depend on changed components
        for (ImpactReport.ImpactedComponent component : components) {
            if (component.getDependents() != null) {
                for (String dependentId : component.getDependents()) {
                    if (graph != null && graph.getNodes() != null) {
                        DependencyGraph.Node node = graph.getNodes().get(dependentId);
                        if (node != null && "SERVICE".equals(node.getType())) {
                            affectedServices.add(node.getName());
                        }
                    }
                }
            }
        }

        // Remove duplicates
        return new ArrayList<>(new LinkedHashSet<>(affectedServices));
    }

    private String formatReportForPR(ImpactReport report) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 🤖 Copilot-Powered Impact Analysis\n\n");

        // Add Copilot badge
        sb.append("![Copilot](https://img.shields.io/badge/Powered%20by-Copilot-blue)\n\n");

        // Risk level with emoji and visual indicator
        String riskEmoji = switch(report.getRiskLevel()) {
            case HIGH -> "🔴";
            case MEDIUM -> "🟡";
            case LOW -> "🟢";
        };

        sb.append(String.format("### %s Risk Level: %s\n\n",
                riskEmoji, report.getRiskLevel()));

        // Add progress bar for risk level
        String riskBar = switch(report.getRiskLevel()) {
            case HIGH -> "🟥🟥🟥🟥🟥 100%";
            case MEDIUM -> "🟨🟨🟨⬜⬜ 60%";
            case LOW -> "🟩⬜⬜⬜⬜ 20%";
        };
        sb.append("**Risk Score:** ").append(riskBar).append("\n\n");

        // Copilot Analysis
        sb.append("### 📊 Copilot Analysis\n\n");
        sb.append(report.getSummary()).append("\n\n");

        // Quick Stats
        sb.append("### 📈 Quick Statistics\n\n");
        sb.append(String.format("- **Impacted Components:** %d\n",
                report.getImpactedComponents() != null ?
                        report.getImpactedComponents().size() : 0));
        sb.append(String.format("- **Affected APIs:** %d\n",
                report.getAffectedAPIs() != null ?
                        report.getAffectedAPIs().size() : 0));
        sb.append(String.format("- **Affected UI Screens:** %d\n",
                report.getAffectedUIScreens() != null ?
                        report.getAffectedUIScreens().size() : 0));
        sb.append(String.format("- **Recommended Tests:** %d\n",
                report.getRecommendedTests() != null ?
                        report.getRecommendedTests().size() : 0));
        sb.append("\n");

        // Impacted Components Table
        if (report.getImpactedComponents() != null &&
                !report.getImpactedComponents().isEmpty()) {
            sb.append("### 🎯 Impacted Components\n\n");
            sb.append("| Component | Type | Impact | Reason |\n");
            sb.append("|-----------|------|--------|--------|\n");

            for (ImpactReport.ImpactedComponent component : report.getImpactedComponents()) {
                String impactEmoji = switch(component.getImpactLevel()) {
                    case "HIGH" -> "🔴";
                    case "MEDIUM" -> "🟡";
                    case "LOW" -> "🟢";
                    default -> "⚪";
                };

                sb.append(String.format("| %s | %s | %s %s | %s |\n",
                        component.getName(),
                        component.getType(),
                        impactEmoji,
                        component.getImpactLevel(),
                        component.getReason()));
            }
            sb.append("\n");
        }

        // Affected APIs
        if (report.getAffectedAPIs() != null && !report.getAffectedAPIs().isEmpty()) {
            sb.append("### 🔌 Affected APIs\n\n");
            report.getAffectedAPIs().forEach(api ->
                    sb.append("- `").append(api).append("`\n"));
            sb.append("\n");
        }

        // Affected UI Screens
        if (report.getAffectedUIScreens() != null &&
                !report.getAffectedUIScreens().isEmpty()) {
            sb.append("### 🖥️ Affected UI Screens\n\n");
            report.getAffectedUIScreens().forEach(screen ->
                    sb.append("- ").append(screen).append("\n"));
            sb.append("\n");
        }

        // Recommended Tests
        if (report.getRecommendedTests() != null &&
                !report.getRecommendedTests().isEmpty()) {
            sb.append("### ✅ Copilot-Recommended Tests\n\n");
            report.getRecommendedTests().forEach(test ->
                    sb.append("- [ ] ").append(test).append("\n"));
            sb.append("\n");
        }

        // Copilot Suggestions
        sb.append("### 💡 Copilot Suggestions\n\n");
        if (report.getRiskLevel() == ImpactReport.RiskLevel.HIGH) {
            sb.append("- ⚠️ Consider adding a feature flag for these changes\n");
            sb.append("- 📝 Request additional code review from domain experts\n");
            sb.append("- 🧪 Run full regression test suite before merging\n");
        } else if (report.getRiskLevel() == ImpactReport.RiskLevel.MEDIUM) {
            sb.append("- 🔍 Review affected integration points\n");
            sb.append("- 📊 Monitor performance after deployment\n");
            sb.append("- ✅ Run integration tests for affected services\n");
        } else {
            sb.append("- ✅ Standard testing should be sufficient\n");
            sb.append("- 📝 Update documentation if needed\n");
        }

        sb.append("\n---\n");
        sb.append("⚡ *Analysis generated automatically by Copilot-Powered PR Impact Analyzer*\n");

        return sb.toString();
    }

    private void addImpactLabels(PullRequestEvent event, ImpactReport report) {
        try {
            List<String> labels = new ArrayList<>();

            // Add risk level label
            labels.add("impact-" + report.getRiskLevel().toString().toLowerCase());

            // Add labels for affected components
            if (report.getAffectedAPIs() != null && !report.getAffectedAPIs().isEmpty()) {
                labels.add("api-changes");
            }
            if (report.getAffectedUIScreens() != null &&
                    !report.getAffectedUIScreens().isEmpty()) {
                labels.add("ui-changes");
            }
            if (report.getImpactedComponents() != null &&
                    report.getImpactedComponents().stream()
                            .anyMatch(c -> "DB".equals(c.getType()))) {
                labels.add("database-changes");
            }

            // Add the labels to the PR
            gitHubService.addLabelsToPR(
                    event.getRepository().getFullName(),
                    event.getNumber(),
                    labels);

        } catch (Exception e) {
            log.error("Error adding labels to PR", e);
        }
    }

    private void postEmptyAnalysis(PullRequestEvent event) {
        try {
            String message = """
                    ## 🤖 Copilot Impact Analysis
                    
                    No files were changed in this pull request. \
                    No impact analysis needed.""";

            gitHubService.postAnalysisComment(
                    event.getRepository().getFullName(),
                    event.getNumber(),
                    message);
        } catch (Exception e) {
            log.error("Error posting empty analysis", e);
        }
    }

    private void logReport(ImpactReport report) {
        log.info("\n" + "=".repeat(50));
        log.info("IMPACT ANALYSIS REPORT");
        log.info("=".repeat(50));
        log.info("PR: #{}", report.getPrNumber());
        log.info("Risk Level: {}", report.getRiskLevel());
        log.info("Impacted Components: {}", report.getImpactedComponents().size());
        log.info("Affected APIs: {}", report.getAffectedAPIs().size());
        log.info("Affected UI Screens: {}", report.getAffectedUIScreens().size());
        log.info("Recommended Tests: {}", report.getRecommendedTests().size());
        log.info("=".repeat(50));
    }
}