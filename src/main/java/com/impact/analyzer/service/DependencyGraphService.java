package com.impact.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.impact.analyzer.model.DependencyNode;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class DependencyGraphService {

    private Graph<String, DefaultEdge> dependencyGraph;
    private Map<String, DependencyNode> nodeDetails;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DependencyGraphService() {
        this.dependencyGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
        this.nodeDetails = new HashMap<>();
        loadOrBuildGraph();
    }

    private void loadOrBuildGraph() {
        File graphFile = new File("dependency-graph.json");
        if (graphFile.exists()) {
            try {
                DependencyNode[] nodes = objectMapper.readValue(graphFile, DependencyNode[].class);
                for (DependencyNode node : nodes) {
                    addNode(node);
                }
                log.info("Loaded dependency graph with {} nodes", nodes.length);
            } catch (Exception e) {
                log.error("Failed to load graph, building new one", e);
                buildGraphFromCode();
            }
        } else {
            buildGraphFromCode();
        }
    }

    private void buildGraphFromCode() {
        log.info("Building dependency graph from source code...");

        // Add sample services for demonstration
        addSampleServices();

        // In production, this would parse the actual codebase
        // parseJavaFiles("src/main/java");

        saveGraph();
    }

    private void addSampleServices() {
        // User Service
        DependencyNode userService = new DependencyNode();
        userService.setId("user-service");
        userService.setType("SERVICE");
        userService.setName("UserService");
        userService.setPath("com.example.service.UserService");
        userService.setDependencies(Arrays.asList("user-repository", "notification-service"));
        userService.setDependents(Arrays.asList("auth-service", "order-service"));

        // Order Service
        DependencyNode orderService = new DependencyNode();
        orderService.setId("order-service");
        orderService.setType("SERVICE");
        orderService.setName("OrderService");
        orderService.setPath("com.example.service.OrderService");
        orderService.setDependencies(Arrays.asList("order-repository", "payment-service", "inventory-service"));
        orderService.setDependents(Arrays.asList("shipping-service"));

        // API Endpoints
        DependencyNode userAPI = new DependencyNode();
        userAPI.setId("api-user");
        userAPI.setType("API");
        userAPI.setName("/api/users");
        userAPI.setPath("POST /api/users");
        userAPI.setDependencies(Arrays.asList("user-service", "validation-service"));

        DependencyNode orderAPI = new DependencyNode();
        orderAPI.setId("api-order");
        orderAPI.setType("API");
        orderAPI.setName("/api/orders");
        orderAPI.setPath("POST /api/orders");
        orderAPI.setDependencies(Arrays.asList("order-service", "payment-service"));

        // Database tables
        DependencyNode userDB = new DependencyNode();
        userDB.setId("db-users");
        userDB.setType("DB");
        userDB.setName("users_table");
        userDB.setDependencies(Arrays.asList());
        userDB.setDependents(Arrays.asList("user-service"));

        DependencyNode orderDB = new DependencyNode();
        orderDB.setId("db-orders");
        orderDB.setType("DB");
        orderDB.setName("orders_table");
        orderDB.setDependencies(Arrays.asList());
        orderDB.setDependents(Arrays.asList("order-service"));

        // UI Components
        DependencyNode userUI = new DependencyNode();
        userUI.setId("ui-user-profile");
        userUI.setType("UI_COMPONENT");
        userUI.setName("UserProfileScreen");
        userUI.setDependencies(Arrays.asList("user-api"));

        DependencyNode orderUI = new DependencyNode();
        orderUI.setId("ui-order-list");
        orderUI.setType("UI_COMPONENT");
        orderUI.setName("OrderListScreen");
        orderUI.setDependencies(Arrays.asList("order-api"));

        addNode(userService);
        addNode(orderService);
        addNode(userAPI);
        addNode(orderAPI);
        addNode(userDB);
        addNode(orderDB);
        addNode(userUI);
        addNode(orderUI);
    }

    private void addNode(DependencyNode node) {
        nodeDetails.put(node.getId(), node);
        dependencyGraph.addVertex(node.getId());

        for (String dep : node.getDependencies()) {
            if (!dependencyGraph.containsVertex(dep)) {
                dependencyGraph.addVertex(dep);
            }
            dependencyGraph.addEdge(node.getId(), dep);
        }
    }

    private void parseJavaFiles(String directory) throws Exception {
        Path startPath = Paths.get(directory);
        Files.walk(startPath)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(this::parseJavaFile);
    }

    private void parseJavaFile(Path filePath) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(filePath.toFile());

            // Find all classes
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String className = cls.getNameAsString();
                String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
                String fullClassName = packageName + "." + className;

                DependencyNode node = new DependencyNode();
                node.setId(className.toLowerCase() + "-node");
                node.setType(determineType(cls));
                node.setName(className);
                node.setPath(fullClassName);
                node.setDependencies(extractDependencies(cu));

                addNode(node);
            });
        } catch (Exception e) {
            log.error("Failed to parse file: " + filePath, e);
        }
    }

    private String determineType(ClassOrInterfaceDeclaration cls) {
        if (cls.getAnnotations().stream().anyMatch(a -> a.getNameAsString().contains("RestController") ||
                a.getNameAsString().contains("Controller"))) {
            return "API";
        } else if (cls.getAnnotations().stream().anyMatch(a -> a.getNameAsString().contains("Service"))) {
            return "SERVICE";
        } else if (cls.getAnnotations().stream().anyMatch(a -> a.getNameAsString().contains("Repository"))) {
            return "DB";
        }
        return "COMPONENT";
    }

    private List<String> extractDependencies(CompilationUnit cu) {
        List<String> deps = new ArrayList<>();
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            String callName = call.getNameAsString();
            if (callName.endsWith("Service") || callName.endsWith("Repository")) {
                deps.add(callName.toLowerCase());
            }
        });
        return deps;
    }

    public Set<String> findImpactedNodes(String changedFile) {
        Set<String> impacted = new HashSet<>();

        // Find which node corresponds to this file
        String startNode = findNodeByPath(changedFile);
        if (startNode == null) {
            log.warn("No node found for file: {}", changedFile);
            return impacted;
        }

        // BFS to find all dependents
        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();

        queue.add(startNode);
        visited.add(startNode);
        impacted.add(startNode);

        while (!queue.isEmpty()) {
            String current = queue.poll();

            // Find all nodes that depend on current node
            for (DependencyNode node : nodeDetails.values()) {
                if (node.getDependencies().contains(current) && !visited.contains(node.getId())) {
                    visited.add(node.getId());
                    impacted.add(node.getId());
                    queue.add(node.getId());
                }
            }
        }

        return impacted;
    }

    private String findNodeByPath(String filePath) {
        // Simple mapping - in production, this would be more sophisticated
        if (filePath.contains("User")) return "user-service";
        if (filePath.contains("Order")) return "order-service";
        if (filePath.contains("Auth")) return "auth-service";
        return null;
    }

    public List<DependencyNode> getImpactedNodesDetailed(Set<String> impactedIds) {
        List<DependencyNode> result = new ArrayList<>();
        for (String id : impactedIds) {
            if (nodeDetails.containsKey(id)) {
                result.add(nodeDetails.get(id));
            }
        }
        return result;
    }

    private void saveGraph() {
        try {
            objectMapper.writeValue(new File("dependency-graph.json"), nodeDetails.values());
            log.info("Saved dependency graph to file");
        } catch (Exception e) {
            log.error("Failed to save graph", e);
        }
    }

    public Graph<String, DefaultEdge> getGraph() {
        return dependencyGraph;
    }
}