package com.google.a2a.client.manager;

import com.google.a2a.client.config.AgentConfig;
import com.travelassistant.common.model.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent 注册表
 * <p>
 * 维护所有已注册的 Agent 及其客户端连接
 */
public class AgentRegistry {
    
    private static final Logger logger = LoggerFactory.getLogger(AgentRegistry.class);
    
    /** Agent 配置映射: name -> config */
    private final Map<String, AgentConfig> agentConfigs;
    
    /** Agent 客户端映射: name -> client */
    private final Map<String, A2AClient> agentClients;
    
    /** Agent Card 缓存: name -> card */
    private final Map<String, AgentCard> agentCards;
    
    /** Agent 状态: name -> isHealthy */
    private final Map<String, Boolean> agentHealth;
    
    public AgentRegistry() {
        this.agentConfigs = new ConcurrentHashMap<>();
        this.agentClients = new ConcurrentHashMap<>();
        this.agentCards = new ConcurrentHashMap<>();
        this.agentHealth = new ConcurrentHashMap<>();
    }
    
    /**
     * 注册 Agent
     */
    public synchronized void register(AgentConfig config, A2AClient client) {
        logger.info("Registering agent: {} at {}", config.name(), config.url());
        
        agentConfigs.put(config.name(), config);
        agentClients.put(config.name(), client);
        agentHealth.put(config.name(), true); // 初始假设健康
        
        logger.info("Agent {} registered successfully", config.name());
    }
    
    /**
     * 注销 Agent
     */
    public synchronized void unregister(String agentName) {
        logger.info("Unregistering agent: {}", agentName);
        
        agentConfigs.remove(agentName);
        agentClients.remove(agentName);
        agentCards.remove(agentName);
        agentHealth.remove(agentName);
        
        logger.info("Agent {} unregistered", agentName);
    }
    
    /**
     * 获取 Agent 客户端
     */
    public Optional<A2AClient> getClient(String agentName) {
        return Optional.ofNullable(agentClients.get(agentName));
    }
    
    /**
     * 获取 Agent 配置
     */
    public Optional<AgentConfig> getConfig(String agentName) {
        return Optional.ofNullable(agentConfigs.get(agentName));
    }
    
    /**
     * 获取 Agent Card
     */
    public Optional<AgentCard> getAgentCard(String agentName) {
        return Optional.ofNullable(agentCards.get(agentName));
    }
    
    /**
     * 缓存 Agent Card
     */
    public void cacheAgentCard(String agentName, AgentCard card) {
        agentCards.put(agentName, card);
        logger.debug("Cached agent card for: {}", agentName);
    }
    
    /**
     * 更新 Agent 健康状态
     */
    public void updateHealth(String agentName, boolean isHealthy) {
        agentHealth.put(agentName, isHealthy);
        logger.debug("Agent {} health status updated: {}", agentName, isHealthy);
    }
    
    /**
     * 检查 Agent 是否健康
     */
    public boolean isHealthy(String agentName) {
        return agentHealth.getOrDefault(agentName, false);
    }
    
    /**
     * 获取所有已注册的 Agent 名称
     */
    public Set<String> getAllAgentNames() {
        return new HashSet<>(agentConfigs.keySet());
    }
    
    /**
     * 获取所有启用的 Agent
     */
    public List<AgentConfig> getEnabledAgents() {
        return agentConfigs.values().stream()
                .filter(AgentConfig::enabled)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取所有健康的 Agent
     */
    public List<String> getHealthyAgents() {
        return agentHealth.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
    
    /**
     * 根据技能查找 Agent
     */
    public List<AgentConfig> findAgentsBySkill(String skill) {
        return agentConfigs.values().stream()
                .filter(AgentConfig::enabled)
                .filter(config -> config.hasSkill(skill))
                .filter(config -> isHealthy(config.name()))
                .collect(Collectors.toList());
    }
    
    /**
     * 根据多个技能查找 Agent（任一匹配）
     */
    public List<AgentConfig> findAgentsByAnySkill(List<String> skills) {
        return agentConfigs.values().stream()
                .filter(AgentConfig::enabled)
                .filter(config -> config.hasAnySkill(skills))
                .filter(config -> isHealthy(config.name()))
                .collect(Collectors.toList());
    }
    
    /**
     * 根据多个技能查找 Agent（全部匹配）
     */
    public List<AgentConfig> findAgentsByAllSkills(List<String> skills) {
        return agentConfigs.values().stream()
                .filter(AgentConfig::enabled)
                .filter(config -> config.hasAllSkills(skills))
                .filter(config -> isHealthy(config.name()))
                .collect(Collectors.toList());
    }
    
    /**
     * 获取注册的 Agent 总数
     */
    public int size() {
        return agentConfigs.size();
    }
    
    /**
     * 清空所有注册
     */
    public synchronized void clear() {
        logger.info("Clearing all agent registrations");
        agentConfigs.clear();
        agentClients.clear();
        agentCards.clear();
        agentHealth.clear();
    }
}

