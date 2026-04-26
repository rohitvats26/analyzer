package com.impact.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.impact.analyzer.model.DependencyNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
@Slf4j
public class DependencyGraphService {

    private Map<String, DependencyNode> nodeDetails;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DependencyGraphService() {
        this.nodeDetails = new HashMap<>();
        buildDefaultGraph();
    }

    private void buildDefaultGraph() {
        log.info("Building default dependency graph...");

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

        // Auth Service
        DependencyNode authService = new DependencyNode();
        authService.setId("auth-service");
        authService.setType("SERVICE");
        authService.setName("AuthService");
        authService.setPath("com.example.service.AuthService");
        authService.setDependencies(Arrays.asList("user-service"));
        authService.setDependents(Arrays.asList("api-gateway"));

        // Payment Service
        DependencyNode paymentService = new DependencyNode();
        paymentService.setId("payment-service");
        paymentService.setType("SERVICE");
        paymentService.setName("PaymentService");
        paymentService.setPath("com.example.service.PaymentService");
        paymentService.setDependencies(Arrays.asList("payment-gateway"));
        paymentService.setDependents(Arrays.asList("order-service"));

        // API Endpoints
        DependencyNode userAPI = new DependencyNode();
        userAPI.setId("api-user");
        userAPI.setType("API");
        userAPI.setName("/api/users");
        userAPI.setPath("POST /api/users");
        userAPI.setDependencies(Arrays.asList("user-service"));
        userAPI.setDependents(Arrays.asList("ui-user-profile"));

        DependencyNode orderAPI = new DependencyNode();
        orderAPI.setId("api-order");
        orderAPI.setType("API");
        orderAPI.setName("/api/orders");
        orderAPI.setPath("POST /api/orders");
        orderAPI.setDependencies(Arrays.asList("order-service"));
        orderAPI.setDependents(Arrays.asList("ui-order-list"));

        DependencyNode authAPI = new DependencyNode();
        authAPI.setId("api-auth");
        authAPI.setType("API");
        authAPI.setName("/api/auth");
        authAPI.setPath("POST /api/auth/login");
        authAPI.setDependencies(Arrays.asList("auth-service"));
        authAPI.setDependents(Arrays.asList("ui-login"));

        // Database tables
        DependencyNode userDB = new DependencyNode();
        userDB.setId("db-users");
        userDB.setType("DB");
        userDB.setName("users_table");
        userDB.setDependencies(new ArrayList<>());
        userDB.setDependents(Arrays.asList("user-service"));

        DependencyNode orderDB = new DependencyNode();
        orderDB.setId("db-orders");
        orderDB.setType("DB");
        orderDB.setName("orders_table");
        orderDB.setDependencies(new ArrayList<>());
        orderDB.setDependents(Arrays.asList("order-service"));

        // UI Components
        DependencyNode userUI = new DependencyNode();
        userUI.setId("ui-user-profile");
        userUI.setType("UI_COMPONENT");
        userUI.setName("UserProfileScreen");
        userUI.setPath("src/ui/screens/UserProfileScreen.js");
        userUI.setDependencies(Arrays.asList("api-user"));
        userUI.setDependents(new ArrayList<>());

        DependencyNode orderUI = new DependencyNode();
        orderUI.setId("ui-order-list");
        orderUI.setType("UI_COMPONENT");
        orderUI.setName("OrderListScreen");
        orderUI.setPath("src/ui/screens/OrderListScreen.js");
        orderUI.setDependencies(Arrays.asList("api-order"));
        orderUI.setDependents(new ArrayList<>());

        DependencyNode loginUI = new DependencyNode();
        loginUI.setId("ui-login");
        loginUI.setType("UI_COMPONENT");
        loginUI.setName("LoginScreen");
        loginUI.setPath("src/ui/screens/LoginScreen.js");
        loginUI.setDependencies(Arrays.asList("api-auth"));
        loginUI.setDependents(new ArrayList<>());

        // Add all nodes
        addNode(userService);
        addNode(orderService);
        addNode(authService);
        addNode(paymentService);
        addNode(userAPI);
        addNode(orderAPI);
        addNode(authAPI);
        addNode(userDB);
        addNode(orderDB);
        addNode(userUI);
        addNode(orderUI);
        addNode(loginUI);

        log.info("Built dependency graph with {} nodes", nodeDetails.size());
    }

    private void addNode(DependencyNode node) {
        nodeDetails.put(node.getId(), node);
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
        String startNode = findNodeByFileName(changedFile);
        if (startNode == null) {
            log.debug("No node found for file: {}", changedFile);
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
            DependencyNode currentNode = nodeDetails.get(current);

            if (currentNode != null && currentNode.getDependents() != null) {
                for (String dependent : currentNode.getDependents()) {
                    if (!visited.contains(dependent)) {
                        visited.add(dependent);
                        impacted.add(dependent);
                        queue.add(dependent);
                        log.debug("Added dependent: {} -> {}", current, dependent);
                    }
                }
            }
        }

        log.info("Found {} impacted nodes for file: {}", impacted.size(), changedFile);
        return impacted;
    }

    private String findNodeByFileName(String fileName) {
        // Map file patterns to node IDs
        String lowerFileName = fileName.toLowerCase();

        if (lowerFileName.contains("user") || lowerFileName.contains("profile")) {
            return "user-service";
        }
        if (lowerFileName.contains("order") || lowerFileName.contains("purchase")) {
            return "order-service";
        }
        if (lowerFileName.contains("auth") || lowerFileName.contains("login")) {
            return "auth-service";
        }
        if (lowerFileName.contains("payment") || lowerFileName.contains("checkout")) {
            return "payment-service";
        }
        if (lowerFileName.contains("api") && lowerFileName.contains("user")) {
            return "api-user";
        }
        if (lowerFileName.contains("ui") && lowerFileName.contains("profile")) {
            return "ui-user-profile";
        }
        if (lowerFileName.contains("ui") && lowerFileName.contains("order")) {
            return "ui-order-list";
        }

        // Default to user service if no match
        return "user-service";
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

    public Map<String, DependencyNode> getAllNodes() {
        return nodeDetails;
    }
}