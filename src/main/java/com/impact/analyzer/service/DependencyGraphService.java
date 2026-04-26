package com.impact.analyzer.service;

import com.impact.analyzer.model.DependencyGraph;
import com.impact.analyzer.model.PullRequestEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class DependencyGraphService {
    
    public DependencyGraph buildDependencyGraph(
            PullRequestEvent.Repository repository) {
        
        DependencyGraph graph = new DependencyGraph();
        graph.setNodes(new HashMap<>());
        graph.setEdges(new ArrayList<>());
        
        try {
            // Analyze project structure to build dependency graph
            // This is a simplified version - in reality, you'd scan the entire codebase
            
            // Example: Build nodes from common patterns
            addNode(graph, "user-service", "SERVICE", 
                Map.of("path", "src/main/java/service/UserService.java"));
            addNode(graph, "user-controller", "API", 
                Map.of("path", "src/main/java/controller/UserController.java"));
            addNode(graph, "user-repository", "DB", 
                Map.of("path", "src/main/java/repository/UserRepository.java"));
            addNode(graph, "auth-service", "SERVICE", 
                Map.of("path", "src/main/java/service/AuthService.java"));
            addNode(graph, "dashboard-ui", "UI", 
                Map.of("path", "src/main/resources/templates/dashboard.html"));
            
            // Build edges (dependencies)
            addEdge(graph, "user-controller", "user-service", "calls", 5);
            addEdge(graph, "user-service", "user-repository", "depends_on", 3);
            addEdge(graph, "user-service", "auth-service", "depends_on", 4);
            addEdge(graph, "dashboard-ui", "user-controller", "calls", 2);
            
            // You can extend this to parse actual code using JavaParser
            // and build real dependency graphs
            
        } catch (Exception e) {
            log.error("Error building dependency graph", e);
        }
        
        return graph;
    }
    
    private void addNode(DependencyGraph graph, String id, String type, 
                        Map<String, Object> metadata) {
        DependencyGraph.Node node = new DependencyGraph.Node();
        node.setId(id);
        node.setType(type);
        node.setName(id.replace("-", " "));
        node.setMetadata(metadata);
        graph.getNodes().put(id, node);
    }
    
    private void addEdge(DependencyGraph graph, String source, String target, 
                        String relationship, int weight) {
        DependencyGraph.Edge edge = new DependencyGraph.Edge();
        edge.setSource(source);
        edge.setTarget(target);
        edge.setRelationship(relationship);
        edge.setWeight(weight);
        graph.getEdges().add(edge);
    }
    
    public List<String> getImpactedServices(
            DependencyGraph graph, 
            List<String> changedFiles) {
        
        List<String> impacted = new ArrayList<>();
        
        for (String file : changedFiles) {
            Optional<DependencyGraph.Node> node = graph.getNodes().values()
                .stream()
                .filter(n -> n.getMetadata() != null && 
                           file.contains((String) n.getMetadata().get("path")))
                .findFirst();
            
            if (node.isPresent()) {
                impacted.add(node.get().getId());
                // Add transitive dependencies
                addTransitiveDependents(graph, node.get().getId(), impacted);
            }
        }
        
        return impacted;
    }
    
    private void addTransitiveDependents(
            DependencyGraph graph, 
            String nodeId, 
            List<String> impacted) {
        
        for (DependencyGraph.Edge edge : graph.getEdges()) {
            if (edge.getSource().equals(nodeId) && 
                !impacted.contains(edge.getTarget())) {
                impacted.add(edge.getTarget());
                addTransitiveDependents(graph, edge.getTarget(), impacted);
            }
        }
    }
}