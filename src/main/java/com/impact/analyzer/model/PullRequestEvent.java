package com.impact.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Date;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor
@AllArgsConstructor
public class PullRequestEvent {
    private String action;
    private Long number;
    private String repository;
    @JsonProperty("pull_request")
    private PullRequest pullRequest;
    private User sender;

    public String getRepository() {
        if (repository != null) {
            return repository;
        }
        if (pullRequest != null && pullRequest.getHead() != null &&
                pullRequest.getHead().getRepo() != null) {
            return pullRequest.getHead().getRepo().getFullName();
        }
        if (pullRequest != null && pullRequest.getBase() != null &&
                pullRequest.getBase().getRepo() != null) {
            return pullRequest.getBase().getRepo().getFullName();
        }
        return null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PullRequest {
        private Long id;
        private Integer number;
        private String title;
        private String body;
        private String url;
        private String state;
        private User user;
        private Head head;
        private Base base;
        @JsonProperty("created_at")
        private Date createdAt;
        @JsonProperty("updated_at")
        private Date updatedAt;
        @JsonProperty("changed_files")
        private int changedFiles;
        @JsonProperty("additions")
        private int additions;
        @JsonProperty("deletions")
        private int deletions;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class User {
        private String login;
        @JsonProperty("avatar_url")
        private String avatarUrl;
        private String url;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Head {
        private String ref;
        private String sha;
        private Repo repo;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Base {
        private String ref;
        private String sha;
        private Repo repo;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Repo {
        private Long id;
        private String name;
        @JsonProperty("full_name")
        private String fullName;
        private String url;
        @JsonProperty("clone_url")
        private String cloneUrl;
    }
}