package com.impact.analyzer.model;

import lombok.Data;
import java.util.*;

@Data
public class NodeMetadata {
    private String type; // SERVICE, API, UI_COMPONENT, DB, DTO, METHOD, CLASS, INTERFACE
    private String filePath;
    private int lineNumber;
    private String packageName;
    private String className;
    private String methodName;
    private String name;
    private String selector;
    private String path;
    private String annotationName;
    private String parent;
    private String language = "Java";
    private List<String> modifiers = new ArrayList<>();
    private Map<String, Object> customProps = new HashMap<>();
    private List<DependencyDetail> outgoingDependencies = new ArrayList<>();
    private List<DependencyDetail> incomingDependencies = new ArrayList<>();

    public void addOutgoingDependency(String target, String type, String reason) {
        outgoingDependencies.add(new DependencyDetail(target, type, reason));
    }

    public void addIncomingDependency(String source, String type, String reason) {
        incomingDependencies.add(new DependencyDetail(source, type, reason));
    }

    @Data
    public static class DependencyDetail {
        private String nodeId;
        private String type;
        private String reason;

        public DependencyDetail(String nodeId, String type, String reason) {
            this.nodeId = nodeId;
            this.type = type;
            this.reason = reason;
        }
    }
}