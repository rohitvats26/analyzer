package com.impact.analyzer.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.impact.analyzer.model.DependencyGraph;
import com.impact.analyzer.model.NodeMetadata;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DependencyAnalyzer {

    @Value("${analyzer.workspace:/tmp/pr-analyzer}")
    private String workspacePath;

    private DependencyGraph graph;
    private Map<String, String> fileToPackageMap;
    private Map<String, List<String>> packageClasses;

    public DependencyGraph analyzeRepository(String repoUrl, String branch, String localPath)
            throws IOException, GitAPIException {

        graph = new DependencyGraph();
        fileToPackageMap = new HashMap<>();
        packageClasses = new HashMap<>();

        // Clone/update repository
        File repoDir = cloneRepository(repoUrl, branch, localPath);

        // Scan all files
        scanDirectory(repoDir);

        // Build cross-file dependencies
        buildCrossFileDependencies();

        // Calculate metrics
        calculateGraphMetrics();

        log.info("Built real dependency graph: {} nodes, {} edges",
                graph.getNodeCount(), graph.getEdgeCount());

        return graph;
    }

    private void scanDirectory(File directory) throws IOException {
        Files.walk(directory.toPath())
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    String fileName = file.toString();
                    if (fileName.endsWith(".java")) {
                        parseJavaFile(file.toFile());
                    } else if (fileName.endsWith(".ts") || fileName.endsWith(".tsx")) {
                        parseTypeScriptFile(file.toFile());
                    } else if (fileName.endsWith(".js") || fileName.endsWith(".jsx")) {
                        parseJavaScriptFile(file.toFile());
                    } else if (fileName.endsWith(".py")) {
                        parsePythonFile(file.toFile());
                    } else if (fileName.endsWith(".go")) {
                        parseGoFile(file.toFile());
                    }
                });
    }

    private void parseJavaFile(File file) {
        try {
            String content = new String(Files.readAllBytes(file.toPath()));

            // Parse with JavaParser
            CompilationUnit cu = StaticJavaParser.parse(content);

            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("default");

            String relativePath = getRelativePath(file);
            fileToPackageMap.put(relativePath, packageName);

            // Parse imports
            Set<String> imports = cu.getImports().stream()
                    .map(ImportDeclaration::getNameAsString)
                    .collect(Collectors.toSet());

            // Parse classes and interfaces
            List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);

            for (ClassOrInterfaceDeclaration classDecl : classes) {
                String className = classDecl.getNameAsString();
                String fullClassName = packageName + "." + className;

                // Add node metadata
                NodeMetadata metadata = new NodeMetadata();
                metadata.setType(classDecl.isInterface() ? "API" : "SERVICE");
                metadata.setFilePath(relativePath);
                metadata.setPackageName(packageName);
                metadata.setClassName(className);
                metadata.setLineNumber(classDecl.getBegin().map(p -> p.line).orElse(0));
                metadata.setModifiers(classDecl.getModifiers().stream()
                        .map(m -> m.getKeyword().asString())
                        .collect(Collectors.toList()));

                graph.addNode(fullClassName, metadata);

                // Track package classes
                packageClasses.computeIfAbsent(packageName, k -> new ArrayList<>()).add(fullClassName);

                // Parse field dependencies
                parseFieldDependencies(classDecl, fullClassName);

                // Parse method dependencies
                parseMethodDependencies(classDecl, fullClassName, imports);

                // Parse inheritance
                parseInheritance(classDecl, fullClassName, imports);

                // Parse annotations
                parseAnnotations(classDecl, fullClassName);
            }

        } catch (Exception e) {
            log.error("Failed to parse Java file: {}", file.getAbsolutePath(), e);
        }
    }

    private void parseFieldDependencies(ClassOrInterfaceDeclaration classDecl, String className) {
        for (FieldDeclaration field : classDecl.getFields()) {
            String fieldType = field.getVariables().get(0).getType().asString();

            // Check if field type is another class in the project
            if (isProjectClass(fieldType)) {
                graph.addDependency(className, fieldType, "FIELD", "field_dependency");
            }

            // Check for annotations indicating dependency injection
            field.getAnnotations().forEach(ann -> {
                String annName = ann.getNameAsString();
                if (annName.equals("Autowired") || annName.equals("Inject") ||
                        annName.equals("Resource")) {
                    graph.addDependency(className, fieldType, "INJECTION", "di_dependency");
                }
            });
        }
    }

    private void parseMethodDependencies(ClassOrInterfaceDeclaration classDecl,
                                         String className,
                                         Set<String> imports) {
        for (MethodDeclaration method : classDecl.getMethods()) {
            String methodName = method.getNameAsString();
            String methodSignature = className + "." + methodName + "()";

            // Add method node
            NodeMetadata methodMetadata = new NodeMetadata();
            methodMetadata.setType("METHOD");
            methodMetadata.setParent(className);
            methodMetadata.setMethodName(methodName);
            graph.addNode(methodSignature, methodMetadata);
            graph.addDependency(className, methodSignature, "CONTAINS", "method_declaration");

            // Parse parameter types
            for (Parameter param : method.getParameters()) {
                String paramType = param.getType().asString();
                if (isProjectClass(paramType)) {
                    graph.addDependency(methodSignature, paramType, "PARAMETER", "method_param");
                }
            }

            // Parse return type
            String returnType = method.getType().asString();
            if (isProjectClass(returnType) && !returnType.equals("void")) {
                graph.addDependency(methodSignature, returnType, "RETURN", "return_type");
            }

            // Parse method calls
            method.findAll(MethodCallExpr.class).forEach(call -> {
                String calledMethod = call.getNameAsString();

                // Try to resolve the called method's class
                Optional<Expression> scope = call.getScope();
                if (scope.isPresent()) {
                    String scopeType = scope.get().calculateResolvedType().describe();
                    if (isProjectClass(scopeType)) {
                        graph.addDependency(methodSignature, scopeType, "CALL", "method_invocation");
                    }
                }
            });

            // Parse object creation (new Something())
            method.findAll(ObjectCreationExpr.class).forEach(creation -> {
                String createdType = creation.getType().getNameAsString();
                if (isProjectClass(createdType)) {
                    graph.addDependency(methodSignature, createdType, "CREATES", "object_creation");
                }
            });
        }
    }

    private void parseInheritance(ClassOrInterfaceDeclaration classDecl,
                                  String className,
                                  Set<String> imports) {
        // Parse extends
        classDecl.getExtendedTypes().forEach(extended -> {
            String parentClass = extended.getNameAsString();
            if (isProjectClass(parentClass)) {
                graph.addDependency(className, parentClass, "EXTENDS", "inheritance");
                // Add reverse dependency for polymorphism
                graph.addDependency(parentClass, className, "POLYMORPHIC", "subclass");
            }
        });

        // Parse implements
        classDecl.getImplementedTypes().forEach(implemented -> {
            String interfaceName = implemented.getNameAsString();
            if (isProjectClass(interfaceName)) {
                graph.addDependency(className, interfaceName, "IMPLEMENTS", "interface_implementation");
            }
        });
    }

    private void parseAnnotations(ClassOrInterfaceDeclaration classDecl, String className) {
        for (AnnotationExpr annotation : classDecl.getAnnotations()) {
            String annotationName = annotation.getNameAsString();

            NodeMetadata annMetadata = new NodeMetadata();
            annMetadata.setType("ANNOTATION");
            annMetadata.setAnnotationName(annotationName);
            graph.addNode(annotationName, annMetadata);
            graph.addDependency(className, annotationName, "ANNOTATED_WITH", "annotation");

            // Spring-specific annotations
            if (annotationName.equals("RestController") || annotationName.equals("Controller")) {
                graph.addDependency(className, "REST_ENDPOINT", "PROVIDES", "rest_api");

                // Parse request mappings
                if (annotation.isNormalAnnotationExpr()) {
                    NormalAnnotationExpr normAnn = (NormalAnnotationExpr) annotation;
                    normAnn.getPairs().forEach(pair -> {
                        if (pair.getNameAsString().equals("path") ||
                                pair.getNameAsString().equals("value")) {
                            String path = pair.getValue().toString().replace("\"", "");
                            NodeMetadata endpointMeta = new NodeMetadata();
                            endpointMeta.setType("ENDPOINT");
                            endpointMeta.setPath(path);
                            graph.addNode(path, endpointMeta);
                            graph.addDependency(className, path, "MAPS_TO", "rest_endpoint");
                        }
                    });
                }
            }

            if (annotationName.equals("Service")) {
                graph.addDependency(className, "SERVICE_LAYER", "BELONGS_TO", "service");
            }

            if (annotationName.equals("Repository")) {
                graph.addDependency(className, "REPOSITORY_LAYER", "BELONGS_TO", "repository");
            }
        }
    }

    private void parseTypeScriptFile(File file) {
        try {
            String content = new String(Files.readAllBytes(file.toPath()));
            String relativePath = getRelativePath(file);

            // Parse imports
            Pattern importPattern = Pattern.compile(
                    "import\\s+(?:\\{[^}]*\\}|\\*\\s+as\\s+\\w+|\\w+)\\s+from\\s+['\"]([^'\"]+)['\"]",
                    Pattern.MULTILINE
            );
            Matcher importMatcher = importPattern.matcher(content);
            Set<String> imports = new HashSet<>();
            while (importMatcher.find()) {
                imports.add(importMatcher.group(1));
            }

            // Parse classes
            Pattern classPattern = Pattern.compile(
                    "export\\s+(?:abstract\\s+)?class\\s+(\\w+)(?:\\s+extends\\s+(\\w+))?(?:\\s+implements\\s+([^{]+))?",
                    Pattern.MULTILINE
            );
            Matcher classMatcher = classPattern.matcher(content);

            while (classMatcher.find()) {
                String className = classMatcher.group(1);
                String extendsClass = classMatcher.group(2);
                String implementsInterfaces = classMatcher.group(3);

                String fullClassName = relativePath + ":" + className;

                NodeMetadata metadata = new NodeMetadata();
                metadata.setType("COMPONENT");
                metadata.setFilePath(relativePath);
                metadata.setClassName(className);
                metadata.setLanguage("TypeScript");
                graph.addNode(fullClassName, metadata);

                // Parse extends
                if (extendsClass != null && !extendsClass.isEmpty()) {
                    graph.addDependency(fullClassName, extendsClass, "EXTENDS", "inheritance");
                }

                // Parse implements
                if (implementsInterfaces != null && !implementsInterfaces.isEmpty()) {
                    String[] interfaces = implementsInterfaces.split(",");
                    for (String iface : interfaces) {
                        graph.addDependency(fullClassName, iface.trim(), "IMPLEMENTS", "interface");
                    }
                }

                // Parse Angular component decorator
                Pattern componentPattern = Pattern.compile(
                        "@Component\\(\\{[^}]*selector:\\s*['\"]([^'\"]+)['\"][^}]*\\}\\)",
                        Pattern.DOTALL
                );
                Matcher componentMatcher = componentPattern.matcher(content);
                if (componentMatcher.find()) {
                    String selector = componentMatcher.group(1);
                    NodeMetadata selectorMeta = new NodeMetadata();
                    selectorMeta.setType("UI_COMPONENT");
                    selectorMeta.setSelector(selector);
                    graph.addNode(selector, selectorMeta);
                    graph.addDependency(fullClassName, selector, "DEFINES", "angular_component");
                }

                // Parse service injections
                Pattern injectPattern = Pattern.compile(
                        "constructor\\([^)]*private\\s+(\\w+)\\s*:\\s*(\\w+)[^)]*\\)",
                        Pattern.MULTILINE
                );
                Matcher injectMatcher = injectPattern.matcher(content);
                while (injectMatcher.find()) {
                    String serviceName = injectMatcher.group(2);
                    graph.addDependency(fullClassName, serviceName, "INJECTS", "di_dependency");
                }
            }

            // Parse function exports
            Pattern functionPattern = Pattern.compile(
                    "export\\s+function\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?::\\s*(\\w+))?",
                    Pattern.MULTILINE
            );
            Matcher functionMatcher = functionPattern.matcher(content);
            while (functionMatcher.find()) {
                String functionName = functionMatcher.group(1);
                String returnType = functionMatcher.group(2);

                String fullFunctionName = relativePath + ":" + functionName;
                NodeMetadata funcMeta = new NodeMetadata();
                funcMeta.setType("FUNCTION");
                funcMeta.setName(functionName);
                graph.addNode(fullFunctionName, funcMeta);

                if (returnType != null && isExternalDependency(returnType)) {
                    graph.addDependency(fullFunctionName, returnType, "RETURNS", "return_type");
                }
            }

        } catch (IOException e) {
            log.error("Failed to parse TypeScript file: {}", file.getAbsolutePath(), e);
        }
    }

    private void parseJavaScriptFile(File file) {
        try {
            String content = new String(Files.readAllBytes(file.toPath()));
            String relativePath = getRelativePath(file);

            // Parse require/import statements
            Pattern requirePattern = Pattern.compile(
                    "(?:const|let|var)\\s+(?:\\{[^}]*\\}|\\w+)\\s*=\\s*require\\(['\"]([^'\"]+)['\"]\\)",
                    Pattern.MULTILINE
            );
            Matcher requireMatcher = requirePattern.matcher(content);
            while (requireMatcher.find()) {
                String dependency = requireMatcher.group(1);
                graph.addDependency(relativePath, dependency, "REQUIRES", "commonjs");
            }

            // Parse ES6 imports
            Pattern importPattern = Pattern.compile(
                    "import\\s+(?:\\{[^}]*\\}|\\*\\s+as\\s+\\w+|\\w+)\\s+from\\s+['\"]([^'\"]+)['\"]",
                    Pattern.MULTILINE
            );
            Matcher importMatcher = importPattern.matcher(content);
            while (importMatcher.find()) {
                String dependency = importMatcher.group(1);
                graph.addDependency(relativePath, dependency, "IMPORTS", "es6_module");
            }

            // Parse class definitions
            Pattern classPattern = Pattern.compile(
                    "class\\s+(\\w+)(?:\\s+extends\\s+(\\w+))?",
                    Pattern.MULTILINE
            );
            Matcher classMatcher = classPattern.matcher(content);
            while (classMatcher.find()) {
                String className = classMatcher.group(1);
                String extendsClass = classMatcher.group(2);

                String fullClassName = relativePath + ":" + className;
                NodeMetadata meta = new NodeMetadata();
                meta.setType("CLASS");
                meta.setClassName(className);
                graph.addNode(fullClassName, meta);

                if (extendsClass != null) {
                    graph.addDependency(fullClassName, extendsClass, "EXTENDS", "inheritance");
                }
            }

        } catch (IOException e) {
            log.error("Failed to parse JavaScript file: {}", file.getAbsolutePath(), e);
        }
    }

    private void parsePythonFile(File file) {
        try {
            String content = new String(Files.readAllBytes(file.toPath()));
            String relativePath = getRelativePath(file);

            // Parse import statements
            Pattern importPattern = Pattern.compile(
                    "^(?:from\\s+(\\S+)\\s+)?import\\s+(\\S+)",
                    Pattern.MULTILINE
            );
            Matcher importMatcher = importPattern.matcher(content);
            while (importMatcher.find()) {
                String module = importMatcher.group(1);
                String imported = importMatcher.group(2);
                String dependency = module != null ? module : imported;
                graph.addDependency(relativePath, dependency, "IMPORTS", "python_module");
            }

            // Parse class definitions
            Pattern classPattern = Pattern.compile(
                    "class\\s+(\\w+)(?:\\(([^)]+)\\))?:",
                    Pattern.MULTILINE
            );
            Matcher classMatcher = classPattern.matcher(content);
            while (classMatcher.find()) {
                String className = classMatcher.group(1);
                String parentClasses = classMatcher.group(2);

                String fullClassName = relativePath + ":" + className;
                NodeMetadata meta = new NodeMetadata();
                meta.setType("CLASS");
                meta.setClassName(className);
                graph.addNode(fullClassName, meta);

                if (parentClasses != null) {
                    String[] parents = parentClasses.split(",");
                    for (String parent : parents) {
                        graph.addDependency(fullClassName, parent.trim(), "EXTENDS", "inheritance");
                    }
                }
            }

            // Parse function definitions and their calls
            Pattern functionPattern = Pattern.compile(
                    "def\\s+(\\w+)\\s*\\([^)]*\\):",
                    Pattern.MULTILINE
            );
            Matcher functionMatcher = functionPattern.matcher(content);
            while (functionMatcher.find()) {
                String functionName = functionMatcher.group(1);
                String fullFunctionName = relativePath + ":" + functionName;

                NodeMetadata funcMeta = new NodeMetadata();
                funcMeta.setType("FUNCTION");
                funcMeta.setName(functionName);
                graph.addNode(fullFunctionName, funcMeta);

                // Find function calls within this function's body
                String functionBody = extractPythonFunctionBody(content, functionMatcher.end());
                Pattern callPattern = Pattern.compile("(\\w+)\\(");
                Matcher callMatcher = callPattern.matcher(functionBody);
                while (callMatcher.find()) {
                    String calledFunction = callMatcher.group(1);
                    graph.addDependency(fullFunctionName, calledFunction, "CALLS", "function_call");
                }
            }

        } catch (IOException e) {
            log.error("Failed to parse Python file: {}", file.getAbsolutePath(), e);
        }
    }

    private void parseGoFile(File file) {
        try {
            String content = new String(Files.readAllBytes(file.toPath()));
            String relativePath = getRelativePath(file);

            // Parse import statements
            Pattern importPattern = Pattern.compile(
                    "import\\s+(?:\\(([^)]+)\\)|\"([^\"]+)\")",
                    Pattern.MULTILINE
            );
            Matcher importMatcher = importPattern.matcher(content);
            while (importMatcher.find()) {
                String imports = importMatcher.group(1);
                if (imports != null) {
                    Pattern singleImport = Pattern.compile("\"([^\"]+)\"");
                    Matcher singleMatcher = singleImport.matcher(imports);
                    while (singleMatcher.find()) {
                        graph.addDependency(relativePath, singleMatcher.group(1), "IMPORTS", "go_package");
                    }
                } else {
                    String singleImport = importMatcher.group(2);
                    if (singleImport != null) {
                        graph.addDependency(relativePath, singleImport, "IMPORTS", "go_package");
                    }
                }
            }

            // Parse struct definitions
            Pattern structPattern = Pattern.compile(
                    "type\\s+(\\w+)\\s+struct\\s*\\{([^}]*)\\}",
                    Pattern.MULTILINE | Pattern.DOTALL
            );
            Matcher structMatcher = structPattern.matcher(content);
            while (structMatcher.find()) {
                String structName = structMatcher.group(1);
                String fields = structMatcher.group(2);

                String fullStructName = relativePath + ":" + structName;
                NodeMetadata meta = new NodeMetadata();
                meta.setType("STRUCT");
                meta.setName(structName);
                graph.addNode(fullStructName, meta);

                // Parse field types as dependencies
                Pattern fieldPattern = Pattern.compile("(\\w+)\\s+(\\w+)");
                Matcher fieldMatcher = fieldPattern.matcher(fields);
                while (fieldMatcher.find()) {
                    String fieldType = fieldMatcher.group(2);
                    if (Character.isUpperCase(fieldType.charAt(0))) { // Exported type
                        graph.addDependency(fullStructName, fieldType, "CONTAINS", "field");
                    }
                }
            }

            // Parse interface definitions
            Pattern interfacePattern = Pattern.compile(
                    "type\\s+(\\w+)\\s+interface\\s*\\{([^}]*)\\}",
                    Pattern.MULTILINE | Pattern.DOTALL
            );
            Matcher interfaceMatcher = interfacePattern.matcher(content);
            while (interfaceMatcher.find()) {
                String interfaceName = interfaceMatcher.group(1);
                String methods = interfaceMatcher.group(2);

                String fullInterfaceName = relativePath + ":" + interfaceName;
                NodeMetadata meta = new NodeMetadata();
                meta.setType("INTERFACE");
                meta.setName(interfaceName);
                graph.addNode(fullInterfaceName, meta);

                // Parse method signatures
                Pattern methodPattern = Pattern.compile("(\\w+)\\([^)]*\\)\\s*(\\w*)");
                Matcher methodMatcher = methodPattern.matcher(methods);
                while (methodMatcher.find()) {
                    String returnType = methodMatcher.group(2);
                    if (!returnType.isEmpty() && Character.isUpperCase(returnType.charAt(0))) {
                        graph.addDependency(fullInterfaceName, returnType, "RETURNS", "return_type");
                    }
                }
            }

        } catch (IOException e) {
            log.error("Failed to parse Go file: {}", file.getAbsolutePath(), e);
        }
    }

    private void buildCrossFileDependencies() {
        // Resolve cross-file dependencies using package and import information
        Map<String, Set<String>> packageDeps = new HashMap<>();

        for (Map.Entry<String, NodeMetadata> entry : graph.getMetadata().entrySet()) {
            String node = entry.getKey();
            NodeMetadata meta = entry.getValue();

            if (meta.getPackageName() != null) {
                packageDeps.computeIfAbsent(meta.getPackageName(), k -> new HashSet<>())
                        .add(node);
            }
        }

        // Build package-level dependencies
        for (Map.Entry<String, NodeMetadata> entry : graph.getMetadata().entrySet()) {
            String node = entry.getKey();
            NodeMetadata meta = entry.getValue();

            // Check dependencies to other packages
            Set<String> deps = graph.getForwardDependencies(node);
            for (String dep : deps) {
                NodeMetadata depMeta = graph.getMetadata().get(dep);
                if (depMeta != null && meta.getPackageName() != null &&
                        depMeta.getPackageName() != null &&
                        !meta.getPackageName().equals(depMeta.getPackageName())) {
                    graph.addPackageDependency(meta.getPackageName(), depMeta.getPackageName());
                }
            }
        }

        log.info("Built cross-file dependencies: {} packages", packageDeps.size());
    }

    private void calculateGraphMetrics() {
        int circularCount = 0;
        int maxDepth = 0;

        for (String node : graph.getMetadata().keySet()) {
            Set<String> visited = new HashSet<>();
            int depth = dfsDepth(node, visited, 0);
            maxDepth = Math.max(maxDepth, depth);

            if (hasCircularDependency(node, new HashSet<>())) {
                circularCount++;
            }
        }

        graph.setMaxDepth(maxDepth);
        graph.setCircularDependencyCount(circularCount);
    }

    private int dfsDepth(String node, Set<String> visited, int depth) {
        if (visited.contains(node)) return depth;
        visited.add(node);

        int maxChildDepth = depth;
        for (String dep : graph.getForwardDependencies(node)) {
            maxChildDepth = Math.max(maxChildDepth, dfsDepth(dep, visited, depth + 1));
        }

        return maxChildDepth;
    }

    private boolean hasCircularDependency(String node, Set<String> visiting) {
        if (visiting.contains(node)) return true;
        visiting.add(node);

        for (String dep : graph.getForwardDependencies(node)) {
            if (hasCircularDependency(dep, new HashSet<>(visiting))) {
                return true;
            }
        }

        return false;
    }

    private boolean isProjectClass(String className) {
        // Check if this class exists in our scanned files
        for (Map.Entry<String, NodeMetadata> entry : graph.getMetadata().entrySet()) {
            if (entry.getKey().endsWith("." + className) ||
                    entry.getKey().endsWith(":" + className)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExternalDependency(String dep) {
        // Check if this is a known library/framework
        String[] knownPrefixes = {"@angular/", "react", "vue", "lodash", "express"};
        for (String prefix : knownPrefixes) {
            if (dep.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String getRelativePath(File file) {
        try {
            Path basePath = Paths.get(workspacePath);
            Path absolutePath = file.toPath();
            return basePath.relativize(absolutePath).toString();
        } catch (Exception e) {
            return file.getName();
        }
    }

    private String extractPythonFunctionBody(String content, int startPos) {
        int braceCount = 0;
        int endPos = startPos;
        boolean inFunction = false;

        for (int i = startPos; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == ':' && !inFunction) {
                inFunction = true;
                braceCount = 1;
            } else if (inFunction) {
                if (c == '\n' && braceCount == 1) {
                    // Check if indentation decreased
                    int nextNonSpace = i + 1;
                    while (nextNonSpace < content.length() && content.charAt(nextNonSpace) == ' ') {
                        nextNonSpace++;
                    }
                    if (nextNonSpace < content.length() && content.charAt(nextNonSpace) != ' ') {
                        endPos = i;
                        break;
                    }
                }
                endPos = i;
            }
        }

        return content.substring(startPos, endPos);
    }

    private File cloneRepository(String repoUrl, String branch, String localPath)
            throws GitAPIException, IOException {

        File repoDir = new File(localPath);
        if (repoDir.exists()) {
            try (Repository repo = new FileRepositoryBuilder()
                    .setGitDir(new File(repoDir, ".git"))
                    .build()) {
                try (Git git = new Git(repo)) {
                    git.pull().call();
                    git.checkout().setName(branch).call();
                }
            }
            return repoDir;
        }

        try (Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setBranch(branch)
                .setDirectory(repoDir)
                .setCloneAllBranches(false)
                .setDepth(1) // Shallow clone for performance
                .call()) {
            return repoDir;
        }
    }

    public List<String> getChangedFilesBetweenCommits(String repoPath, String oldCommit, String newCommit)
            throws IOException, GitAPIException {

        List<String> changedFiles = new ArrayList<>();

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath, ".git"))
                .build()) {

            try (Git git = new Git(repo)) {
                ObjectReader reader = repo.newObjectReader();
                CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
                CanonicalTreeParser newTreeIter = new CanonicalTreeParser();

                RevCommit oldCommitObj = repo.parseCommit(repo.resolve(oldCommit));
                RevCommit newCommitObj = repo.parseCommit(repo.resolve(newCommit));

                RevTree oldTree = oldCommitObj.getTree();
                RevTree newTree = newCommitObj.getTree();

                oldTreeIter.reset(reader, oldTree.getId());
                newTreeIter.reset(reader, newTree.getId());

                try (DiffFormatter formatter = new DiffFormatter(new ByteArrayOutputStream())) {
                    formatter.setRepository(repo);
                    List<DiffEntry> entries = formatter.scan(oldTreeIter, newTreeIter);

                    for (DiffEntry entry : entries) {
                        changedFiles.add(entry.getNewPath());
                    }
                }
            }
        }

        return changedFiles;
    }
}