package com.impact.analyzer.model;

import lombok.Data;

import java.util.List;

@Data
public class PullRequestEvent {
    private String action;
    private int number;
    private PullRequest pullRequest;
    private Repository repository;
    
    @Data
    public static class PullRequest {
        private String title;
        private String body;
        private String head;
        private String base;
        private String htmlUrl;
        private User user;
        private List<FileChange> changedFiles;
    }
    
    @Data
    public static class FileChange {
        private String filename;
        private String status; // added, modified, removed
        private String patch;
        private int additions;
        private int deletions;
    }
    
    @Data
    public static class Repository {
        private String fullName;
        private String cloneUrl;
        private String defaultBranch;
    }
    
    @Data
    public static class User {
        private String login;
        private String avatarUrl;
    }
}

