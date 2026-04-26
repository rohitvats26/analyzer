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
    private Repo repository;
    @JsonProperty("pull_request")
    private PullRequest pullRequest;
    private User sender;


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
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Repo {
        private Long id;
        private String name;

        @JsonProperty("full_name")
        private String fullName;

        private String url;

        @JsonProperty("html_url")
        private String htmlUrl;

        @JsonProperty("clone_url")
        private String cloneUrl;

        private String description;
        private boolean fork;

        @JsonProperty("private")
        private boolean isPrivate;

        @JsonProperty("created_at")
        private Date createdAt;

        @JsonProperty("updated_at")
        private Date updatedAt;

        @JsonProperty("pushed_at")
        private Date pushedAt;

        @JsonProperty("stargazers_count")
        private int stargazersCount;

        @JsonProperty("watchers_count")
        private int watchersCount;

        @JsonProperty("forks_count")
        private int forksCount;

        @JsonProperty("open_issues_count")
        private int openIssuesCount;

        @JsonProperty("default_branch")
        private String defaultBranch;

        private User owner;

        @JsonProperty("node_id")
        private String nodeId;

        @JsonProperty("size")
        private int size;
    }
}