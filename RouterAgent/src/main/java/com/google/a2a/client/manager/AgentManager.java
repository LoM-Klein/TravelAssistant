package com.google.a2a.client.manager;

import com.google.a2a.client.Exception.A2AClientException;
import com.google.a2a.client.config.AgentConfig;
import com.google.a2a.client.config.AgentsConfigLoader;
import com.google.a2a.client.manager.monitor.AgentHealthMonitor;
import com.travelassistant.common.model.AgentCard;
import com.travelassistant.common.model.JSONRPCResponse;
import com.travelassistant.common.model.TaskSendParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 管理器
 * <p>
 * 多 Agent 协作的核心管理类，提供：
 * - Agent 注册和发现
 * - Agent 选择和路由
 * - 健康监控
 * - 任务分发
 */
public class AgentManager {
    
    private static final Logger logger = LoggerFactory.getLogger(AgentManager.class);
    
    private final AgentRegistry registry;
    private final AgentDiscovery discovery;
    private final AgentHealthMonitor healthMonitor;

    
    /**
     * 构造函数 - 创建内部的 AgentRegistry
     */
    public AgentManager() {
        this.registry = new AgentRegistry();
        this.discovery = new AgentDiscovery(registry);
        this.healthMonitor = new AgentHealthMonitor(registry, discovery);
    }
    
    /**
     * 获取内部的 AgentRegistry 实例
     * 重要：必须使用这个方法获取 registry，确保使用的是同一个实例
     */
    public AgentRegistry getRegistry() {
        return registry;
    }
    
    /**
     * 从配置文件初始化
     */
    public static AgentManager fromConfigFile(String configPath) throws IOException {
        logger.info("Initializing AgentManager from config file: {}", configPath);
        
        AgentManager manager = new AgentManager();
        List<AgentConfig> configs = AgentsConfigLoader.loadFromYaml(configPath);
        
        manager.loadAgents(configs);
        return manager;
    }
    
    /**
     * 从 classpath 资源初始化
     */
    public static AgentManager fromClasspath(String resourcePath) throws IOException {
        logger.info("Initializing AgentManager from classpath: {}", resourcePath);
        
        AgentManager manager = new AgentManager();
        List<AgentConfig> configs = AgentsConfigLoader.loadFromClasspath(resourcePath);
        
        manager.loadAgents(configs);
        return manager;
    }
    
    /**
     * 加载 Agent 配置列表
     */
    public void loadAgents(List<AgentConfig> configs) {
        logger.info("Loading {} agent configurations", configs.size());
        
        int successCount = 0;
        for (AgentConfig config : configs) {
            if (!config.enabled()) {
                logger.info("Skipping disabled agent: {}", config.name());
                continue;
            }
            
            boolean success = discovery.discoverAgent(config);
            if (success) {
                successCount++;
            }
        }
        
        logger.info("Successfully loaded {}/{} agents", successCount, configs.size());
    }
    
    /**
     * 异步加载 Agent 配置
     */
    public CompletableFuture<Void> loadAgentsAsync(List<AgentConfig> configs) {
        logger.info("Asynchronously loading {} agent configurations", configs.size());
        
        List<CompletableFuture<Boolean>> futures = configs.stream()
                .filter(AgentConfig::enabled)
                .map(discovery::discoverAgentAsync)
                .toList();
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> logger.info("All agents loaded asynchronously"));
    }
    
    /**
     * 手动注册 Agent
     */
    public void registerAgent(AgentConfig config) {
        discovery.discoverAgent(config);
    }
    
    /**
     * 注销 Agent
     */
    public void unregisterAgent(String agentName) {
        registry.unregister(agentName);
        logger.info("Agent {} unregistered", agentName);
    }
    

    /**
     * 获取 Agent 客户端
     */
    public Optional<A2AClient> getAgent(String agentName) {
        return registry.getClient(agentName);
    }
    

    /**
     * 发送任务到指定 Agent
     */
    public JSONRPCResponse sendTask(String agentName, TaskSendParams params) 
            throws A2AClientException {
        var clientOpt = registry.getClient(agentName);
        if (clientOpt.isEmpty()) {
            throw new A2AClientException("Agent not found: " + agentName);
        }
        
        logger.info("Sending task to agent: {}", agentName);
        return clientOpt.get().sendTask(params);
    }

    
    /**
     * 获取所有已注册的 Agent 信息
     */
    public List<AgentInfo> getAllAgents() {
        return registry.getAllAgentNames().stream()
                .map(name -> {
                    var config = registry.getConfig(name).orElse(null);
                    var card = registry.getAgentCard(name).orElse(null);
                    boolean healthy = registry.isHealthy(name);
                    
                    if (card == null) {
                        logger.warn("Agent {} has no AgentCard cached. This may affect intent analysis.", name);
                    }
                    
                    return new AgentInfo(name, config, card, healthy);
                })
                .toList();
    }
    
    /**
     * 获取健康的 Agent 列表
     */
    public List<String> getHealthyAgents() {
        return registry.getHealthyAgents();
    }
    
    /**
     * 启动健康监控
     */
    public void startHealthMonitoring() {
        healthMonitor.start();
        logger.info("Health monitoring started");
    }
    
    /**
     * 停止健康监控
     */
    public void stopHealthMonitoring() {
        healthMonitor.stop();
        logger.info("Health monitoring stopped");
    }
    
    /**
     * 关闭 AgentManager
     */
    public void shutdown() {
        logger.info("Shutting down AgentManager");
        healthMonitor.stop();
        discovery.shutdown();
        registry.clear();
        logger.info("AgentManager shutdown complete");
    }
    
    /**
     * Agent 信息记录
     */
    public record AgentInfo(
        String name,
        AgentConfig config,
        AgentCard card,
        boolean healthy
    ) {}
}

