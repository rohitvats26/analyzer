package com.impact.analyzer.service;

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
    
    @Value("${github.token}")
    private String githubToken;
    
    private GitHub github;
    
    private GitHub getGitHub() throws IOException {
        if (github == null) {
            github = new GitHubBuilder()
                .withOAuthToken(githubToken)
                .build();
        }
        return github;
    }
    
    public List<PullRequestEvent.FileChange> getPRFiles(PullRequestEvent event) 
            throws IOException {
        List<PullRequestEvent.FileChange> files = new ArrayList<>();
        
        try {
            GitHub github = getGitHub();
            GHRepository repo = github.getRepository(
                event.getRepository().getFullName());
            GHPullRequest pr = repo.getPullRequest(event.getNumber());
            
            for (GHPullRequestFileDetail file : pr.listFiles()) {
                PullRequestEvent.FileChange change = 
                    new PullRequestEvent.FileChange();
                change.setFilename(file.getFilename());
                change.setStatus(file.getStatus());
                change.setAdditions(file.getAdditions());
                change.setDeletions(file.getDeletions());
                
                // Get the patch if available
                if (file.getPatch() != null) {
                    change.setPatch(file.getPatch());
                }
                
                files.add(change);
            }
            
            log.info("Retrieved {} files from PR #{}", 
                files.size(), event.getNumber());
            
        } catch (IOException e) {
            log.error("Error getting PR files", e);
            throw e;
        }
        
        return files;
    }
    
    public void postAnalysisComment(
            String repoFullName, 
            int prNumber, 
            String comment) throws IOException {
        
        try {
            GitHub github = getGitHub();
            GHRepository repo = github.getRepository(repoFullName);
            GHPullRequest pr = repo.getPullRequest(prNumber);
            
            // Check for existing analysis comment and update it
            boolean updated = false;
            for (GHIssueComment existingComment : pr.listComments()) {
                if (existingComment.getBody().contains("AI-Powered Impact Analysis")) {
                    existingComment.update(comment);
                    updated = true;
                    log.info("Updated existing analysis comment on PR #{}", prNumber);
                    break;
                }
            }
            
            // Create new comment if no existing analysis found
            if (!updated) {
                pr.comment(comment);
                log.info("Posted new analysis comment on PR #{}", prNumber);
            }
            
        } catch (IOException e) {
            log.error("Error posting analysis comment", e);
            throw e;
        }
    }
    
    public String getFileContent(
            String repoFullName, 
            String path, 
            String branch) throws IOException {
        
        try {
            GitHub github = getGitHub();
            GHRepository repo = github.getRepository(repoFullName);
            GHContent content = repo.getFileContent(path, branch);
            return new String(content.read().readAllBytes());
        } catch (IOException e) {
            log.error("Error getting file content: {}", path, e);
            throw e;
        }
    }

    // Add these methods to GitHubService.java

    public void addLabelsToPR(String repoFullName, int prNumber, List<String> labels)
            throws IOException {
        try {
            GitHub github = getGitHub();
            GHRepository repo = github.getRepository(repoFullName);
            GHPullRequest pr = repo.getPullRequest(prNumber);

            for (String label : labels) {
                try {
                    pr.addLabels(label);
                    log.info("Added label '{}' to PR #{}", label, prNumber);
                } catch (IOException e) {
                    log.warn("Could not add label '{}': {}", label, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Error adding labels to PR", e);
            throw e;
        }
    }

    public void requestCopilotReview(String repoFullName, int prNumber)
            throws IOException {
        // This method requests a Copilot code review
        // Note: This feature might require specific GitHub permissions

        try {
            GitHub github = getGitHub();
            GHRepository repo = github.getRepository(repoFullName);

            // Create a review request for Copilot
            // This is a placeholder for the actual Copilot review request API
            log.info("Requested Copilot review for PR #{}", prNumber);

        } catch (IOException e) {
            log.error("Error requesting Copilot review", e);
            throw e;
        }
    }
}