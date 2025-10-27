package com.google.a2a.client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Router Agent Spring Boot 应用
 */
@SpringBootApplication(scanBasePackages = "com.google.a2a.client")
public class RouterAgentApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(RouterAgentApplication.class, args);
    }
}

