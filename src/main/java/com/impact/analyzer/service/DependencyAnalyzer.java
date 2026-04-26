package com.impact.analyzer.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.impact.analyzer.model.DependencyGraph;
import com.impact.analyzer.model.NodeMetadata;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.regex.*;

@Service
@Slf4j
public class DependencyAnalyzer {
    
    private DependencyGraph graph = new DependencyGraph();
    
    public DependencyGraph analyzeRepository(String repoUrl, String branch, String localPath) 
            throws IOException, GitAPIException {
        
        // Clone/update repository
        File repoDir = cloneRepository(repoUrl, branch, localPath);
        
        // Walk through files
        Files.walk(repoDir.toPath())
            .filter(Files::isRegularFile)
            .forEach(file -> parseFile(file.toFile()));
        
        // Build reverse dependencies
        buildReverseDependencies();
        
        return graph;
    }
    
    private void parseFile(File file) {
        String fileName = file.getName();
        
        if (fileName.endsWith(".java")) {
            parseJavaFile(file);
        } else if (fileName.endsWith(".ts") || fileName.endsWith(".tsx") || fileName.endsWith(".js")) {
            parseTypescriptFile(file);
        } else if (fileName.endsWith(".html") || fileName.endsWith(".vue")) {
            parseAngularFile(file);
        }
    }
    
    private void parseJavaFile(File file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            
            String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("default");
            
            // Parse classes and their dependencies
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
                String className = packageName + "." + classDecl.getNameAsString();
                
                // Add metadata
                NodeMetadata metadata = new NodeMetadata();
                metadata.setType(classDecl.isInterface() ? "API" : "SERVICE");
                metadata.setFilePath(file.getAbsolutePath());
                graph.getMetadata().put(className, metadata);
                
                // Parse field dependencies
                classDecl.getFields().forEach(field -> {
                    String fieldType = field.getVariable(0).getType().asString();
                    parseTypeForDependencies(fieldType, className, file);
                });
                
                // Parse method parameters and return types
                classDecl.getMethods().forEach(method -> {
                    parseMethodDependencies(method, className);
                    
                    // Check for REST annotations
                    method.getAnnotations().stream()
                        .filter(ann -> ann.getNameAsString().matches(".*(GetMapping|PostMapping|PutMapping|DeleteMapping|RequestMapping)"))
                        .forEach(ann -> {
                            String apiPath = extractApiPath(ann);
                            String apiNode = className + "." + method.getNameAsString() + "()";
                            NodeMetadata apiMetadata = new NodeMetadata();
                            apiMetadata.setType("API");
                            apiMetadata.setFilePath(file.getAbsolutePath());
                            apiMetadata.getCustomProps().put("path", apiPath);
                            graph.getMetadata().put(apiNode, apiMetadata);
                            graph.addDependency(className, apiNode);
                        });
                });
            });
            
        } catch (Exception e) {
            log.error("Failed to parse Java file: " + file, e);
        }
    }
    
    private void parseMethodDependencies(MethodDeclaration method, String className) {
        // Parse parameter types
        method.getParameters().forEach(param -> {
            String paramType = param.getType().asString();
            parseTypeForDependencies(paramType, className, null);
        });
        
        // Parse return type
        method.getType().asString();
        
        // Parse method calls
        method.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call -> {
            String calledMethod = call.getNameAsString();
            String calledClass = call.getScope()
                .map(scope -> scope.toString())
                .orElse("unknown");
            
            if (!calledClass.isEmpty()) {
                graph.addDependency(className, calledClass + "." + calledMethod);
            }
        });
    }
    
    private void parseTypeForDependencies(String type, String fromClass, File file) {
        // Check for service classes
        if (type.contains("Service") || type.contains("Repository") || type.contains("Client")) {
            graph.addDependency(fromClass, type);
        }
        
        // Check for DTO/models
        if (type.matches(".*(Dto|DTO|Request|Response|Model)$")) {
            graph.addDependency(fromClass, type);
        }
    }
    
    private void parseTypescriptFile(File file) {
        try {
            String content = Files.readString(file.toPath());
            
            // Parse imports
            Pattern importPattern = Pattern.compile("import\\s+.*?from\\s+['\"](.+?)['\"]");
            Matcher matcher = importPattern.matcher(content);
            
            String currentComponent = extractAngularComponentName(content, file);
            
            while (matcher.find()) {
                String importPath = matcher.group(1);
                if (!importPath.startsWith(".")) {
                    graph.addDependency(currentComponent, importPath);
                }
            }
            
            // Parse service injections
            Pattern servicePattern = Pattern.compile("constructor\\([^)]*private\\s+(\\w+)\\s*:\\s*(\\w+Service)");
            Matcher serviceMatcher = servicePattern.matcher(content);
            while (serviceMatcher.find()) {
                String serviceName = serviceMatcher.group(2);
                graph.addDependency(currentComponent, serviceName);
            }
            
        } catch (IOException e) {
            log.error("Failed to parse TS file: " + file, e);
        }
    }
    
    private void parseAngularFile(File file) {
        try {
            String content = Files.readString(file.toPath());
            
            // Parse Angular component template references
            Pattern selectorPattern = Pattern.compile("<(app-[\\w-]+)");
            Matcher matcher = selectorPattern.matcher(content);
            
            String currentComponent = extractAngularComponentName(content, file);
            
            while (matcher.find()) {
                String childComponent = matcher.group(1);
                graph.addDependency(currentComponent, childComponent);
            }
            
            // Parse API calls
            Pattern apiPattern = Pattern.compile("this\\.\\w+\\.(get|post|put|delete)\\(['\"]([^'\"]+)['\"]");
            Matcher apiMatcher = apiPattern.matcher(content);
            while (apiMatcher.find()) {
                String apiEndpoint = apiMatcher.group(2);
                NodeMetadata apiMeta = new NodeMetadata();
                apiMeta.setType("API_CALL");
                apiMeta.getCustomProps().put("endpoint", apiEndpoint);
                graph.getMetadata().put(apiEndpoint, apiMeta);
                graph.addDependency(currentComponent, apiEndpoint);
            }
            
        } catch (IOException e) {
            log.error("Failed to parse Angular file: " + file, e);
        }
    }
    
    private String extractAngularComponentName(String content, File file) {
        Pattern componentPattern = Pattern.compile("selector:\\s*['\"]([^'\"]+)['\"]");
        Matcher matcher = componentPattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return file.getName().replaceAll("\\.(ts|html)$", "");
    }
    
    private String extractApiPath(AnnotationExpr annotation) {
        return annotation.toString().replaceAll(".*\"([^\"]+)\".*", "$1");
    }
    
    private void buildReverseDependencies() {
        // We already maintain reverseDeps in addDependency method
    }
    
    private File cloneRepository(String repoUrl, String branch, String localPath) 
            throws GitAPIException, IOException {
        
        File repoDir = new File(localPath);
        if (repoDir.exists()) {
            // Pull latest changes
            try (Repository repo = new FileRepositoryBuilder()
                    .setGitDir(new File(repoDir, ".git"))
                    .build()) {
                try (Git git = new Git(repo)) {
                    git.pull().call();
                }
            }
            return repoDir;
        }
        
        // Clone new repository
        try (Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setBranch(branch)
                .setDirectory(repoDir)
                .call()) {
            return repoDir;
        }
    }
}