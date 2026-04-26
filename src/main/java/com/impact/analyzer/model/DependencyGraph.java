package com.impact.analyzer.model;

import lombok.Data;
import java.util.*;

@Data
public class DependencyGraph {
    private Map<String, Set<String>> forwardDeps = new HashMap<>();
    private Map<String, Set<String>> reverseDeps = new HashMap<>();
    private Map<String, NodeMetadata> metadata = new HashMap<>();
    
    public void addDependency(String from, String to) {
        forwardDeps.computeIfAbsent(from, k -> new HashSet<>()).add(to);
        reverseDeps.computeIfAbsent(to, k -> new HashSet<>()).add(from);
    }
    
    public Set<String> getReverseDependencies(String node) {
        return reverseDeps.getOrDefault(node, new HashSet<>());
    }
    
    public Set<String> getForwardDependencies(String node) {
        return forwardDeps.getOrDefault(node, new HashSet<>());
    }
    
    public Set<String> getAllImpactedNodes(String startNode) {
        Set<String> impacted = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(startNode);
        
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (impacted.add(current)) {
                queue.addAll(getReverseDependencies(current));
            }
        }
        return impacted;
    }
}