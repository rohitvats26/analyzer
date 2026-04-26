package com.impact.analyzer.model;

import lombok.Data;

@Data
public class ChangedFile {
    private String filename;
    private String status; // added, modified, removed
    private int additions;
    private int deletions;
    private String patch;
    private String blobUrl;
    private String rawUrl;
}
