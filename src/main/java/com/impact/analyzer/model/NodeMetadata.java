package com.impact.analyzer.model;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class NodeMetadata {
    private String type; // SERVICE, API, UI_COMPONENT, DB, DTO
    private String filePath;
    private int lineNumber;
    private Map<String, Object> customProps = new HashMap<>();
}
