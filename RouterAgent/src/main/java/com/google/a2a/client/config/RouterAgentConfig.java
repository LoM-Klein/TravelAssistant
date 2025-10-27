package com.google.a2a.client.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.a2a.client.component.analyzer.IntentAnalyzer;
import com.google.a2a.client.service.LLMService;
import com.google.a2a.client.service.impl.SpringAILLMService;
import com.google.a2a.client.service.impl.StreamingRouterServiceImpl;
import com.google.a2a.client.manager.AgentManager;
import com.google.a2a.client.component.orchestrator.AgentOrchestrator;
import com.google.a2a.client.service.impl.RouterServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

/**
 * Router Agent 配置类
 */
@Configuration
public class RouterAgentConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(RouterAgentConfig.class);
    
    @Value("${router.agents.config:agents-config.yaml}")
    private String agentsConfigPath;
    
    /**
     * AgentManager Bean
     */
    @Bean
    public AgentManager agentManager() throws IOException {
        logger.info("Initializing AgentManager with config: {}", agentsConfigPath);
        
        AgentManager manager = AgentManager.fromClasspath(agentsConfigPath);
        
        // 启动健康监控
        manager.startHealthMonitoring();
        logger.info("AgentManager initialized with {} agents", 
                manager.getAllAgents().size());
        
        return manager;
    }
    
    /**
     * LLM Service Bean
     * 使用 Spring AI 的 ChatModel 实现
     */
    @Bean
    public LLMService llmService(ChatModel chatModel) {
        logger.info("Initializing LLM Service (SpringAILLMService with DashScope)");
        return new SpringAILLMService(chatModel);
    }
    
    /**
     * Intent Analyzer Bean
     */
    @Bean
    public IntentAnalyzer intentAnalyzer(AgentManager agentManager, LLMService llmService) {
        logger.info("Initializing IntentAnalyzer");
        return new IntentAnalyzer(agentManager, llmService);
    }
    
    /**
     * Agent Orchestrator Bean
     */
    @Bean
    public AgentOrchestrator agentOrchestrator(AgentManager agentManager) {
        logger.info("Initializing AgentOrchestrator");
        return new AgentOrchestrator(agentManager);
    }
    
    /**
     * Router Service Bean
     */
    @Bean
    public RouterServiceImpl routerService(
            IntentAnalyzer intentAnalyzer,
            AgentOrchestrator orchestrator,
            LLMService llmService) {
        logger.info("Initializing RouterService");
        return new RouterServiceImpl(intentAnalyzer, orchestrator, llmService);
    }
    
    /**
     * ObjectMapper Bean
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
    
    /**
     * WebClient Builder Bean (for reactive streaming)
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        logger.info("Initializing WebClient Builder for streaming");
        return WebClient.builder();
    }
    
    /**
     * Aggregator Agent Bean
     */
    @Bean
    public com.google.a2a.client.aggregator.AggregatorAgent aggregatorAgent(LLMService llmService) {
        logger.info("Initializing AggregatorAgent");
        return new com.google.a2a.client.aggregator.AggregatorAgent(llmService);
    }
    
    /**
     * Streaming Router Service Bean
     */
    @Bean
    public com.google.a2a.client.service.impl.StreamingRouterServiceImpl streamingRouterService(
            IntentAnalyzer intentAnalyzer,
            WebClient.Builder webClientBuilder,
            com.google.a2a.client.aggregator.AggregatorAgent aggregatorAgent) {
        logger.info("Initializing StreamingRouterService");
        return new StreamingRouterServiceImpl(intentAnalyzer, webClientBuilder, aggregatorAgent);
    }
}

