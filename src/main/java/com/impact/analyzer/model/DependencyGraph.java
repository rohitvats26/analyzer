package com.impact.analyzer.model;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Slf4j
public class DependencyGraph {
    private Map<String, Set<String>> forwardDeps = new ConcurrentHashMap<>();
    private Map<String, Set<String>> reverseDeps = new ConcurrentHashMap<>();
    private Map<String, NodeMetadata> metadata = new ConcurrentHashMap<>();
    private Map<String, Set<String>> packageDeps = new ConcurrentHashMap<>();
    private Map<String, Integer> nodeMetrics = new HashMap<>();

    private int maxDepth;
    private int circularDependencyCount;
    private double averageFanOut;
    private double averageFanIn;

    // Constructor
    public DependencyGraph() {
        log.info("Initialized new DependencyGraph");
    }

    // Get forward dependencies (what this node depends on)
    public Set<String> getForwardDependencies(String node) {
        return forwardDeps.getOrDefault(node, new HashSet<>());
    }

    // Get reverse dependencies (what depends on this node)
    public Set<String> getReverseDependencies(String node) {
        return reverseDeps.getOrDefault(node, new HashSet<>());
    }

    // Add node with metadata
    public void addNode(String nodeId, NodeMetadata meta) {
        metadata.put(nodeId, meta);
        forwardDeps.putIfAbsent(nodeId, ConcurrentHashMap.newKeySet());
        reverseDeps.putIfAbsent(nodeId, ConcurrentHashMap.newKeySet());
        log.debug("Added node: {}", nodeId);
    }

    // Add dependency with type and reason
    public void addDependency(String from, String to, String type, String reason) {
        if (from.equals(to)) {
            log.debug("Skipping self-dependency: {}", from);
            return;
        }

        // Add forward dependency
        forwardDeps.computeIfAbsent(from, k -> ConcurrentHashMap.newKeySet()).add(to);

        // Add reverse dependency
        reverseDeps.computeIfAbsent(to, k -> ConcurrentHashMap.newKeySet()).add(from);

        // Store dependency metadata
        if (metadata.containsKey(from)) {
            metadata.get(from).addOutgoingDependency(to, type, reason);
        }
        if (metadata.containsKey(to)) {
            metadata.get(to).addIncomingDependency(from, type, reason);
        }

        log.debug("Added dependency: {} -> {} (type: {}, reason: {})", from, to, type, reason);
    }

    // Simplified add dependency
    public void addDependency(String from, String to) {
        addDependency(from, to, "UNKNOWN", "unspecified");
    }

    // Add multiple dependencies
    public void addDependencies(String from, Set<String> toList) {
        for (String to : toList) {
            addDependency(from, to);
        }
    }

    // Add package-level dependency
    public void addPackageDependency(String fromPackage, String toPackage) {
        if (!fromPackage.equals(toPackage)) {
            packageDeps.computeIfAbsent(fromPackage, k -> ConcurrentHashMap.newKeySet())
                    .add(toPackage);
            log.debug("Added package dependency: {} -> {}", fromPackage, toPackage);
        }
    }

    // Get all upstream nodes (reverse traversal)
    public Set<String> getAllUpstreamNodes(String startNode) {
        Set<String> upstream = new LinkedHashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(startNode);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            Set<String> deps = getReverseDependencies(current);
            for (String dep : deps) {
                if (upstream.add(dep)) {
                    queue.add(dep);
                }
            }
        }

        log.debug("Found {} upstream nodes for {}", upstream.size(), startNode);
        return upstream;
    }

    // Get all downstream nodes (forward traversal)
    public Set<String> getAllDownstreamNodes(String startNode) {
        Set<String> downstream = new LinkedHashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(startNode);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            Set<String> deps = getForwardDependencies(current);
            for (String dep : deps) {
                if (downstream.add(dep)) {
                    queue.add(dep);
                }
            }
        }

        log.debug("Found {} downstream nodes for {}", downstream.size(), startNode);
        return downstream;
    }

    // Alias for getAllUpstreamNodes (maintains backward compatibility)
    public Set<String> getAllImpactedNodes(String startNode) {
        return getAllUpstreamNodes(startNode);
    }

    // Get nodes by type
    public Set<String> getNodesByType(String type) {
        Set<String> nodes = new HashSet<>();
        for (Map.Entry<String, NodeMetadata> entry : metadata.entrySet()) {
            if (type.equals(entry.getValue().getType())) {
                nodes.add(entry.getKey());
            }
        }
        return nodes;
    }

    // Get nodes by file path
    public Set<String> getNodesByFile(String filePath) {
        Set<String> nodes = new HashSet<>();
        for (Map.Entry<String, NodeMetadata> entry : metadata.entrySet()) {
            if (filePath.equals(entry.getValue().getFilePath())) {
                nodes.add(entry.getKey());
            } else if (entry.getValue().getFilePath() != null &&
                    entry.getValue().getFilePath().endsWith(filePath)) {
                nodes.add(entry.getKey());
            }
        }
        return nodes;
    }

    // Get nodes by package
    public Set<String> getNodesByPackage(String packageName) {
        Set<String> nodes = new HashSet<>();
        for (Map.Entry<String, NodeMetadata> entry : metadata.entrySet()) {
            if (packageName.equals(entry.getValue().getPackageName())) {
                nodes.add(entry.getKey());
            }
        }
        return nodes;
    }

    // Get node metadata
    public NodeMetadata getNodeMetadata(String nodeId) {
        return metadata.get(nodeId);
    }

    // Check if dependency exists
    public boolean hasDependency(String from, String to) {
        return forwardDeps.getOrDefault(from, new HashSet<>()).contains(to);
    }

    // Check if reverse dependency exists
    public boolean hasReverseDependency(String from, String to) {
        return reverseDeps.getOrDefault(from, new HashSet<>()).contains(to);
    }

    // Remove node and all its dependencies
    public void removeNode(String node) {
        // Remove from forward deps
        forwardDeps.remove(node);

        // Remove from reverse deps
        reverseDeps.remove(node);

        // Remove from metadata
        metadata.remove(node);

        // Remove edges pointing to this node
        for (Set<String> deps : forwardDeps.values()) {
            deps.remove(node);
        }
        for (Set<String> revDeps : reverseDeps.values()) {
            revDeps.remove(node);
        }

        log.debug("Removed node: {}", node);
    }

    // Get critical path (longest chain of dependencies)
    public List<String> getCriticalPath() {
        List<String> criticalPath = new ArrayList<>();
        String deepestNode = null;
        int maxDepth = 0;

        for (String node : metadata.keySet()) {
            int depth = getAllDownstreamNodes(node).size();
            if (depth > maxDepth) {
                maxDepth = depth;
                deepestNode = node;
            }
        }

        if (deepestNode != null) {
            criticalPath.addAll(getAllDownstreamNodes(deepestNode));
        }

        return criticalPath;
    }

    // Calculate impact scores for each node
    public Map<String, Integer> getNodeImpactScores() {
        Map<String, Integer> scores = new HashMap<>();
        for (String node : metadata.keySet()) {
            int upstreamCount = getAllUpstreamNodes(node).size();
            int downstreamCount = getAllDownstreamNodes(node).size();
            scores.put(node, upstreamCount + downstreamCount);
        }
        return scores;
    }

    // Get nodes with highest impact
    public List<String> getHighestImpactNodes(int limit) {
        Map<String, Integer> scores = getNodeImpactScores();
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    // Calculate metrics
    public void calculateMetrics() {
        // Calculate average fan-out
        if (!forwardDeps.isEmpty()) {
            averageFanOut = forwardDeps.values().stream()
                    .mapToInt(Set::size)
                    .average()
                    .orElse(0.0);
        }

        // Calculate average fan-in
        if (!reverseDeps.isEmpty()) {
            averageFanIn = reverseDeps.values().stream()
                    .mapToInt(Set::size)
                    .average()
                    .orElse(0.0);
        }

        // Detect circular dependencies
        circularDependencyCount = detectCircularDependencies();

        log.info("Graph metrics - Nodes: {}, Edges: {}, Fan-out: {:.2f}, Fan-in: {:.2f}, Circular: {}",
                getNodeCount(), getEdgeCount(), averageFanOut, averageFanIn, circularDependencyCount);
    }

    // Detect circular dependencies
    private int detectCircularDependencies() {
        int circularCount = 0;
        Set<String> visited = new HashSet<>();

        for (String node : metadata.keySet()) {
            if (!visited.contains(node)) {
                Set<String> path = new HashSet<>();
                if (hasCycle(node, path, visited)) {
                    circularCount++;
                }
            }
        }

        return circularCount;
    }

    private boolean hasCycle(String node, Set<String> path, Set<String> visited) {
        if (path.contains(node)) {
            return true;
        }
        if (visited.contains(node)) {
            return false;
        }

        path.add(node);
        visited.add(node);

        for (String dep : getForwardDependencies(node)) {
            if (hasCycle(dep, path, visited)) {
                return true;
            }
        }

        path.remove(node);
        return false;
    }

    // Export graph in DOT format for visualization
    public String exportToDot() {
        StringBuilder dot = new StringBuilder();
        dot.append("digraph DependencyGraph {\n");
        dot.append("  rankdir=LR;\n");
        dot.append("  node [shape=box, style=filled, fillcolor=lightblue];\n\n");

        // Add nodes
        for (Map.Entry<String, NodeMetadata> entry : metadata.entrySet()) {
            String nodeId = entry.getKey().replace("\"", "\\\"");
            String nodeType = entry.getValue().getType();
            String color = getNodeColor(nodeType);
            dot.append(String.format("  \"%s\" [label=\"%s\\n(%s)\", fillcolor=%s];\n",
                    nodeId, nodeId, nodeType, color));
        }

        dot.append("\n");

        // Add edges
        for (Map.Entry<String, Set<String>> entry : forwardDeps.entrySet()) {
            String from = entry.getKey().replace("\"", "\\\"");
            for (String to : entry.getValue()) {
                String toEscaped = to.replace("\"", "\\\"");
                dot.append(String.format("  \"%s\" -> \"%s\";\n", from, toEscaped));
            }
        }

        dot.append("}\n");
        return dot.toString();
    }

    private String getNodeColor(String type) {
        switch (type) {
            case "SERVICE":
                return "lightgreen";
            case "API":
                return "lightcoral";
            case "UI_COMPONENT":
                return "lightyellow";
            case "DATABASE":
                return "lightblue";
            case "DTO":
                return "lightgray";
            default:
                return "white";
        }
    }

    // Get summary statistics
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_nodes", getNodeCount());
        stats.put("total_edges", getEdgeCount());
        stats.put("avg_fan_out", averageFanOut);
        stats.put("avg_fan_in", averageFanIn);
        stats.put("circular_dependencies", circularDependencyCount);
        stats.put("max_depth", maxDepth);
        stats.put("nodes_by_type", getNodeCountByType());
        return stats;
    }

    private Map<String, Integer> getNodeCountByType() {
        Map<String, Integer> countByType = new HashMap<>();
        for (NodeMetadata meta : metadata.values()) {
            countByType.merge(meta.getType(), 1, Integer::sum);
        }
        return countByType;
    }

    // Clear all data
    public void clear() {
        forwardDeps.clear();
        reverseDeps.clear();
        metadata.clear();
        packageDeps.clear();
        nodeMetrics.clear();
        maxDepth = 0;
        circularDependencyCount = 0;
        averageFanOut = 0;
        averageFanIn = 0;
        log.info("Cleared dependency graph");
    }

    // Add this method to get node count
    public int getNodeCount() {
        return metadata.size();
    }

    // Add this method to get edge count
    public int getEdgeCount() {
        return forwardDeps.values().stream().mapToInt(Set::size).sum();
    }

    // Add this method to get max depth
    public int getMaxDepth() {
        return maxDepth;
    }

    // Add this method to set max depth
    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    // Add this method to get circular dependency count
    public int getCircularDependencyCount() {
        return circularDependencyCount;
    }

    // Add this method to set circular dependency count
    public void setCircularDependencyCount(int circularDependencyCount) {
        this.circularDependencyCount = circularDependencyCount;
    }
}