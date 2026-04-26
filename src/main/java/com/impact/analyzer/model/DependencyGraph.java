package com.impact.analyzer.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class DependencyGraph {
    private Map<String, Node> nodes;
    private List<Edge> edges;
    
    @Data
    public static class Node {
        private String id;
        private String type;
        private String name;
        private Map<String, Object> metadata;
    }
    
    @Data
    public static class Edge {
        private String source;
        private String target;
        private String relationship;
        private int weight;
    }
}
