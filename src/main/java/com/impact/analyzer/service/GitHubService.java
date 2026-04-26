package com.impact.analyzer.service;

import com.impact.analyzer.model.ChangedFile;
import com.impact.analyzer.model.PullRequestEvent;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class GitHubService {

    private GitHub github;

    @Value("${github.token:}")
    private String githubToken;

    public GitHubService() throws IOException {
        if (githubToken != null && !githubToken.isEmpty()) {
            this.github = new GitHubBuilder().withOAuthToken(githubToken).build();
        } else {
            log.warn("No GitHub token provided, using unauthenticated client (limited API calls)");
            this.github = new GitHubBuilder().build();
        }
    }

    public List<ChangedFile> getChangedFiles(PullRequestEvent event) throws IOException {
        List<ChangedFile> changedFiles = new ArrayList<>();

        String repoFullName = event.getRepository();
        if (repoFullName == null && event.getPullRequest() != null && event.getPullRequest().getHead() != null) {
            repoFullName = event.getPullRequest().getHead().getRepo().getFull_name();
        }

        if (repoFullName == null) {
            log.error("Cannot determine repository name from event");
            return changedFiles;
        }

        GHRepository repo = github.getRepository(repoFullName);
        GHPullRequest pr = repo.getPullRequest(event.getPullRequest().getNumber());

        // Use GHPullRequestFileDetail instead of GHIssue.PRFile
        List<GHPullRequestFileDetail> files = pr.listFiles().toList();

        for (GHPullRequestFileDetail file : files) {
            ChangedFile changedFile = new ChangedFile();
            changedFile.setFilename(file.getFilename());
            changedFile.setStatus(getStatusType(file.getStatus()));
            changedFile.setAdditions(file.getAdditions());
            changedFile.setDeletions(file.getDeletions());
            changedFile.setPatch(file.getPatch());
            changedFile.setBlobUrl(file.getBlobUrl().toString());
            changedFile.setRawUrl(file.getRawUrl().toString());
            changedFiles.add(changedFile);
        }

        log.info("Retrieved {} changed files from PR #{}", changedFiles.size(), event.getPullRequest().getNumber());
        return changedFiles;
    }

    private String getStatusType(String status) {
        if (status == null) return "modified";
        switch (status.toLowerCase()) {
            case "added": return "added";
            case "removed": return "removed";
            case "renamed": return "renamed";
            case "copied": return "copied";
            case "changed": return "modified";
            default: return "modified";
        }
    }

    public void postComment(String repo, int prNumber, String comment) throws IOException {
        GHRepository ghRepo = github.getRepository(repo);
        GHPullRequest pr = ghRepo.getPullRequest(prNumber);
        pr.comment(comment);
        log.info("Posted comment to PR #{}: {}", prNumber, comment.substring(0, Math.min(100, comment.length())));
    }

    public void addLabel(String repo, int prNumber, String label) throws IOException {
        GHRepository ghRepo = github.getRepository(repo);
        GHPullRequest pr = ghRepo.getPullRequest(prNumber);
        pr.addLabels(label);
        log.info("Added label '{}' to PR #{}", label, prNumber);
    }

    public void addLabels(String repo, int prNumber, List<String> labels) throws IOException {
        GHRepository ghRepo = github.getRepository(repo);
        GHPullRequest pr = ghRepo.getPullRequest(prNumber);
        for (String label : labels) {
            pr.addLabels(label);
        }
        log.info("Added {} labels to PR #{}", labels.size(), prNumber);
    }
}