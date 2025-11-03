package com.google.a2a.client.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.a2a.client.component.aggregator.AggregatorAgent;
import com.google.a2a.client.component.analyzer.IntentAnalyzer;
import com.google.a2a.client.service.LLMService;
import com.google.a2a.client.service.impl.SpringAILLMService;
import com.google.a2a.client.manager.AgentManager;
import com.google.a2a.client.manager.AgentRegistry;
import com.google.a2a.client.component.orchestrator.AgentOrchestrator;
import com.google.a2a.client.service.impl.RouterServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
     * AgentRegistry Bean
     * 重要：必须从 AgentManager 中获取，确保使用同一个实例
     */
    @Bean
    public AgentRegistry agentRegistry(AgentManager agentManager) {
        logger.info("Exposing AgentRegistry from AgentManager");
        return agentManager.getRegistry();
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
     * Aggregator Agent Bean
     */
    @Bean
    public AggregatorAgent aggregatorAgent(LLMService llmService) {
        logger.info("Initializing AggregatorAgent");
        return new AggregatorAgent(llmService);
    }
    
    // StreamingRouterServiceImpl 使用 @Service 注解，Spring 会自动扫描注册
    // 不需要手动配置 Bean
}

