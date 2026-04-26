package com.impact.analyzer.controller;

import com.impact.analyzer.model.PullRequestEvent;
import com.impact.analyzer.service.ImpactAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {
    
    private final ImpactAnalysisService impactAnalysisService;
    private final String webhookSecret = System.getenv("WEBHOOK_SECRET");
    
    @PostMapping("/github")
    public ResponseEntity<String> handleGitHubWebhook(
            @RequestBody String payload,
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader("X-Hub-Signature-256") String signature) {
        
        log.info("📨 Received GitHub event: {}", eventType);
        
        // Verify webhook signature
        if (!verifySignature(payload, signature)) {
            log.error("❌ Invalid webhook signature");
            return ResponseEntity.status(403).body("Invalid signature");
        }
        
        // Handle pull request events
        if ("pull_request".equals(eventType)) {
            return handlePullRequestEvent(payload);
        }
        
        return ResponseEntity.ok("Event received");
    }
    
    private ResponseEntity<String> handlePullRequestEvent(String payload) {
        try {
            // Parse the event
            PullRequestEvent event = parsePullRequestEvent(payload);
            
            // Check if this is a PR opened or synchronized event
            if (event.getAction().equals("opened") || 
                event.getAction().equals("synchronize") ||
                event.getAction().equals("reopened")) {
                
                log.info("🔍 Analyzing PR #{}: {}", 
                    event.getNumber(), 
                    event.getPullRequest().getTitle());
                
                // Trigger async analysis
                impactAnalysisService.analyzePullRequest(event);
                
                return ResponseEntity.ok("Analysis started for PR #" + event.getNumber());
            }
            
            return ResponseEntity.ok("Event type not processed: " + event.getAction());
            
        } catch (Exception e) {
            log.error("Error processing PR event", e);
            return ResponseEntity.status(500).body("Error processing event");
        }
    }
    
    private boolean verifySignature(String payload, String signatureHeader) {
        if (webhookSecret == null || signatureHeader == null) {
            return true; // Skip verification if secret not configured
        }
        
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String calculatedSignature = "sha256=" + HexFormat.of().formatHex(hmacBytes);
            
            return calculatedSignature.equals(signatureHeader);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Error verifying signature", e);
            return false;
        }
    }
    
    private PullRequestEvent parsePullRequestEvent(String payload) {
        // Parse GitHub webhook payload
        // This is a simplified version - you'd use a proper JSON parser
        com.google.gson.Gson gson = new com.google.gson.Gson();
        return gson.fromJson(payload, PullRequestEvent.class);
    }
}