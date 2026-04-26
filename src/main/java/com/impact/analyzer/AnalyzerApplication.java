package com.impact.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AnalyzerApplication {

	public static void main(String[] args) {
		SpringApplication.run(AnalyzerApplication.class, args);
		System.out.println("🚀 PR Impact Analyzer Service Started!");
		System.out.println("📡 Listening for GitHub webhook events...");
	}

}
