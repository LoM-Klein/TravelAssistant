package com.google.a2a.client.manager;

import com.google.a2a.client.Exception.A2AClientException;
import com.google.a2a.client.config.AgentConfig;
import com.travelassistant.common.model.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 发现服务
 * <p>
 * 负责发现和连接 Agent，获取其 Agent Card 信息
 */
public class AgentDiscovery {
    
    private static final Logger logger = LoggerFactory.getLogger(AgentDiscovery.class);
    private final ExecutorService executorService;
    private final AgentRegistry registry;
    
    public AgentDiscovery(AgentRegistry registry) {
        this.registry = registry;
        this.executorService = Executors.newCachedThreadPool();
    }
    
    /**
     * 同步发现并注册 Agent
     */
    public boolean discoverAgent(AgentConfig config) {
        logger.info("Discovering agent: {} at {}", config.name(), config.url());
        
        try {
            // 创建客户端
            A2AClient client = new A2AClient(config.url());
            
            // 获取 Agent Card
            AgentCard card = client.getAgentCard();
            
            // 注册到注册表
            registry.register(config, client);
            registry.cacheAgentCard(config.name(), card);
            
            logger.info("Successfully discovered agent: {} - {}", 
                    config.name(), card.name());
            logger.info("Agent skills: {}", card.skills());
            
            return true;
            
        } catch (A2AClientException e) {
            logger.error("Failed to discover agent: {} - {}", 
                    config.name(), e.getMessage());
            registry.updateHealth(config.name(), false);
            return false;
        }
    }
    
    /**
     * 异步发现并注册 Agent
     */
    public CompletableFuture<Boolean> discoverAgentAsync(AgentConfig config) {
        return CompletableFuture.supplyAsync(() -> discoverAgent(config), executorService);
    }
    
    /**
     * 重新发现 Agent（刷新 Agent Card）
     */
    public boolean rediscoverAgent(String agentName) {
        logger.info("Rediscovering agent: {}", agentName);
        
        var configOpt = registry.getConfig(agentName);
        if (configOpt.isEmpty()) {
            logger.warn("Agent {} not found in registry", agentName);
            return false;
        }
        
        var clientOpt = registry.getClient(agentName);
        if (clientOpt.isEmpty()) {
            logger.warn("Client for agent {} not found", agentName);
            return false;
        }
        
        try {
            // 重新获取 Agent Card
            AgentCard card = clientOpt.get().getAgentCard();
            registry.cacheAgentCard(agentName, card);
            registry.updateHealth(agentName, true);
            
            logger.info("Successfully rediscovered agent: {}", agentName);
            return true;
            
        } catch (A2AClientException e) {
            logger.error("Failed to rediscover agent: {} - {}", agentName, e.getMessage());
            registry.updateHealth(agentName, false);
            return false;
        }
    }
    
    /**
     * 验证 Agent 连接
     */
    public boolean validateAgent(String agentName) {
        logger.debug("Validating agent: {}", agentName);
        
        var clientOpt = registry.getClient(agentName);
        if (clientOpt.isEmpty()) {
            return false;
        }
        
        try {
            // 尝试获取 Agent Card 以验证连接
            clientOpt.get().getAgentCard();
            registry.updateHealth(agentName, true);
            return true;
        } catch (A2AClientException e) {
            logger.warn("Agent {} validation failed: {}", agentName, e.getMessage());
            registry.updateHealth(agentName, false);
            return false;
        }
    }
    
    /**
     * 关闭发现服务
     */
    public void shutdown() {
        logger.info("Shutting down agent discovery service");
        executorService.shutdown();
    }
}

