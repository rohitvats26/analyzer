package com.impact.analyzer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
@Slf4j
public class WebhookSecurity {
    
    @Value("${github.webhook-secret:}")
    private String webhookSecret;
    
    public boolean verifySignature(String payload, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            log.warn("No webhook secret configured - skipping signature verification!");
            return true; // Skip verification if no secret is set
        }
        
        if (signatureHeader == null || signatureHeader.isEmpty()) {
            log.error("Missing X-Hub-Signature-256 header");
            return false;
        }
        
        try {
            String expectedSignature = computeSignature(payload);
            return MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Failed to verify webhook signature", e);
            return false;
        }
    }
    
    private String computeSignature(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(
            webhookSecret.getBytes(StandardCharsets.UTF_8), 
            "HmacSHA256"
        );
        mac.init(secretKeySpec);
        byte[] signatureBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        
        // Convert to hex and add prefix
        StringBuilder hexString = new StringBuilder("sha256=");
        for (byte b : signatureBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        
        return hexString.toString();
    }
}