package com.impact.analyzer.model;

import lombok.Data;

import java.util.List;

@Data
public class DependencyNode {
    private String id;
    private String type; // SERVICE, API, DB, UI_COMPONENT
    private String name;
    private String path;
    private List<String> dependencies;
    private List<String> dependents;
    private DependencyNode.Metadata metadata;

    @Data
    public static class Metadata {
        private String description;
        private List<String> annotations;
        private String owner;
    }
}
