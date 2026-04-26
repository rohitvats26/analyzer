package com.impact.analyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.impact.analyzer.model.ChangedFile;
import com.impact.analyzer.model.PullRequestEvent;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class GitHubService {

    @Value("${github.token:}")
    private String githubToken;

    private final ObjectMapper objectMapper = new ObjectMapper();


    public List<ChangedFile> getChangedFiles(PullRequestEvent event) throws Exception {
        List<ChangedFile> changedFiles = new ArrayList<>();

        String repoFullName = event.getRepository().getFullName();
        int prNumber = event.getPullRequest().getNumber();

        // Use GitHub REST API directly
        String apiUrl = String.format("https://api.github.com/repos/%s/pulls/%d/files",
                repoFullName, prNumber);

        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("User-Agent", "PR-Impact-Analyzer");

        if (githubToken != null && !githubToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "token " + githubToken);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }

            JsonNode files = objectMapper.readTree(response.toString());
            for (JsonNode file : files) {
                ChangedFile changedFile = new ChangedFile();
                changedFile.setFilename(file.get("filename").asText());
                changedFile.setStatus(file.get("status").asText());
                changedFile.setAdditions(file.get("additions").asInt());
                changedFile.setDeletions(file.get("deletions").asInt());

                if (file.has("patch")) {
                    changedFile.setPatch(file.get("patch").asText());
                }
                if (file.has("blob_url")) {
                    changedFile.setBlobUrl(file.get("blob_url").asText());
                }
                if (file.has("raw_url")) {
                    changedFile.setRawUrl(file.get("raw_url").asText());
                }

                changedFiles.add(changedFile);
            }
            log.info("Retrieved {} changed files from PR #{}", changedFiles.size(), prNumber);
        } else {
            // Read error response
            StringBuilder errorResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    errorResponse.append(line);
                }
            }
            log.error("GitHub API returned {} for PR #{}/files. Error: {}",
                    responseCode, prNumber, errorResponse.toString());
            throw new RuntimeException("GitHub API error: " + responseCode);
        }

        conn.disconnect();
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

    public void postComment(String repo, int prNumber, String comment) throws Exception {
        String apiUrl = String.format("https://api.github.com/repos/%s/issues/%d/comments",
                repo, prNumber);

        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "PR-Impact-Analyzer");

        if (githubToken != null && !githubToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "token " + githubToken);
        }

        conn.setDoOutput(true);

        String jsonBody = String.format("{\"body\": %s}",
                objectMapper.writeValueAsString(comment));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes());
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 201) {
            log.error("Failed to post comment: HTTP {}", responseCode);
            throw new RuntimeException("Failed to post comment: " + responseCode);
        } else {
            log.info("Posted comment to PR #{}", prNumber);
        }

        conn.disconnect();
    }

    public void addLabel(String repo, int prNumber, String label) throws Exception {
        String apiUrl = String.format("https://api.github.com/repos/%s/issues/%d/labels",
                repo, prNumber);

        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "PR-Impact-Analyzer");

        if (githubToken != null && !githubToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "token " + githubToken);
        }

        conn.setDoOutput(true);

        String jsonBody = String.format("{\"labels\": [\"%s\"]}", label);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes());
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            log.error("Failed to add label: HTTP {}", responseCode);
            throw new RuntimeException("Failed to add label: " + responseCode);
        } else {
            log.info("Added label '{}' to PR #{}", label, prNumber);
        }

        conn.disconnect();
    }
}