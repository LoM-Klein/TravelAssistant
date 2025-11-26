package com.google.a2a.client.manager;

import com.google.a2a.client.config.AgentConfig;
import com.google.a2a.client.pool.A2AClientPool;
import com.google.a2a.client.pool.A2AClientPoolConfig;
import com.travelassistant.common.model.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent 注册表
 * <p>
 * 维护所有已注册的 Agent 及其客户端连接池
 * <p>
 * 🔥 新特性：连接池支持
 * - 每个 Agent 维护独立的连接池
 * - 支持连接复用，提升性能
 * - 自动管理连接生命周期
 * 
 * 注意：不使用 @Component 注解，由 AgentManager 创建并管理
 * 避免 Spring 自动创建导致多实例问题
 */
public class AgentRegistry {
    
    private static final Logger logger = LoggerFactory.getLogger(AgentRegistry.class);
    
    /** Agent 配置映射: name -> config */
    private final Map<String, AgentConfig> agentConfigs;
    
    /** Agent 客户端映射: name -> client（向后兼容，已弃用） */
    @Deprecated
    private final Map<String, A2AClient> agentClients;
    
    /** 🔥 Agent 连接池映射: name -> pool */
    private final Map<String, A2AClientPool> agentPools;
    
    /** Agent Card 缓存: name -> card */
    private final Map<String, AgentCard> agentCards;
    
    /** Agent 状态: name -> isHealthy */
    private final Map<String, Boolean> agentHealth;
    
    /** 连接池配置 */
    private final A2AClientPoolConfig poolConfig;
    
    /** 是否启用连接池 */
    private final boolean poolEnabled;
    
    /**
     * 创建 AgentRegistry（不启用连接池）
     */
    public AgentRegistry() {
        this(null, false);
    }
    
    /**
     * 创建 AgentRegistry（可选启用连接池）
     * 
     * @param poolConfig 连接池配置（如果为 null，使用默认配置）
     * @param poolEnabled 是否启用连接池
     */
    public AgentRegistry(A2AClientPoolConfig poolConfig, boolean poolEnabled) {
        this.agentConfigs = new ConcurrentHashMap<>();
        this.agentClients = new ConcurrentHashMap<>();
        this.agentPools = new ConcurrentHashMap<>();
        this.agentCards = new ConcurrentHashMap<>();
        this.agentHealth = new ConcurrentHashMap<>();
        this.poolConfig = poolConfig != null ? poolConfig : new A2AClientPoolConfig();
        this.poolEnabled = poolEnabled;
        
        logger.info("AgentRegistry initialized with pool support: {}", poolEnabled);
    }
    
    /**
     * 注册 Agent
     * 
     * @param config Agent 配置
     * @param client Agent 客户端（如果启用连接池，此参数会被忽略）
     */
    public synchronized void register(AgentConfig config, A2AClient client) {
        logger.info("Registering agent: {} at {}", config.name(), config.url());
        
        agentConfigs.put(config.name(), config);
        agentHealth.put(config.name(), true); // 初始假设健康
        
        if (poolEnabled) {
            // 🔥 启用连接池：创建连接池
            A2AClientPool pool = new A2AClientPool(config.name(), config.url(), poolConfig);
            agentPools.put(config.name(), pool);
            logger.info("Agent {} registered with connection pool (maxTotal={})", 
                    config.name(), poolConfig.getMaxTotal());
        } else {
            // 传统模式：直接使用客户端
            agentClients.put(config.name(), client);
            logger.info("Agent {} registered with single client", config.name());
        }
    }
    
    /**
     * 注销 Agent
     */
    public synchronized void unregister(String agentName) {
        logger.info("Unregistering agent: {}", agentName);
        
        agentConfigs.remove(agentName);
        agentCards.remove(agentName);
        agentHealth.remove(agentName);
        
        // 清理连接池
        if (poolEnabled) {
            A2AClientPool pool = agentPools.remove(agentName);
            if (pool != null) {
                pool.close();
                logger.info("Connection pool closed for agent: {}", agentName);
            }
        } else {
            agentClients.remove(agentName);
        }
        
        logger.info("Agent {} unregistered", agentName);
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 🔥 连接池相关方法
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * 从连接池借用客户端
     * <p>
     * 🔥 推荐使用此方法（支持连接复用）
     * 
     * @param agentName Agent 名称
     * @return A2AClient 实例
     * @throws IllegalStateException 如果未启用连接池
     */
    public A2AClient borrowClient(String agentName) {
        if (!poolEnabled) {
            throw new IllegalStateException("Connection pool is not enabled. Use getClient() instead.");
        }
        
        A2AClientPool pool = agentPools.get(agentName);
        if (pool == null) {
            throw new IllegalArgumentException("No connection pool found for agent: " + agentName);
        }
        
        return pool.borrowClient();
    }
    
    /**
     * 将客户端归还到连接池
     * <p>
     * 🔥 必须与 borrowClient() 配对使用
     * 
     * @param agentName Agent 名称
     * @param client 要归还的客户端
     */
    public void returnClient(String agentName, A2AClient client) {
        if (!poolEnabled) {
            logger.warn("Connection pool is not enabled, returnClient() has no effect");
            return;
        }
        
        A2AClientPool pool = agentPools.get(agentName);
        if (pool != null) {
            pool.returnClient(client);
        } else {
            logger.warn("No connection pool found for agent: {}", agentName);
        }
    }
    
    /**
     * 标记客户端为无效（连接失败时调用）
     * 
     * @param agentName Agent 名称
     * @param client 要标记为无效的客户端
     */
    public void invalidateClient(String agentName, A2AClient client) {
        if (!poolEnabled) {
            logger.warn("Connection pool is not enabled, invalidateClient() has no effect");
            return;
        }
        
        A2AClientPool pool = agentPools.get(agentName);
        if (pool != null) {
            pool.invalidateClient(client);
            logger.info("Client invalidated for agent: {}", agentName);
        } else {
            logger.warn("No connection pool found for agent: {}", agentName);
        }
    }
    
    /**
     * 获取连接池统计信息
     * 
     * @param agentName Agent 名称
     * @return 连接池统计，如果未启用连接池或 Agent 不存在则返回 null
     */
    public A2AClientPool.PoolStats getPoolStats(String agentName) {
        if (!poolEnabled) {
            return null;
        }
        
        A2AClientPool pool = agentPools.get(agentName);
        return pool != null ? pool.getStats() : null;
    }
    
    /**
     * 获取所有 Agent 的连接池统计
     * 
     * @return Agent 名称 -> 连接池统计
     */
    public Map<String, A2AClientPool.PoolStats> getAllPoolStats() {
        if (!poolEnabled) {
            return Collections.emptyMap();
        }
        
        Map<String, A2AClientPool.PoolStats> stats = new HashMap<>();
        for (String agentName : agentPools.keySet()) {
            A2AClientPool.PoolStats poolStats = getPoolStats(agentName);
            if (poolStats != null) {
                stats.put(agentName, poolStats);
            }
        }
        return stats;
    }
    
    /**
     * 检查是否启用了连接池
     */
    public boolean isPoolEnabled() {
        return poolEnabled;
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
        AgentCard card = agentCards.get(agentName);
        if (card == null) {
            logger.warn("AgentCard not found for agent: {}. Available agents: {}", 
                    agentName, agentCards.keySet());
        }
        return Optional.ofNullable(card);
    }
    
    /**
     * 缓存 Agent Card
     */
    public void cacheAgentCard(String agentName, AgentCard card) {
        if (card == null) {
            logger.warn("Attempted to cache null AgentCard for agent: {}", agentName);
            return;
        }
        agentCards.put(agentName, card);
        logger.info("Cached agent card for: {} - Name: {}, Skills: {}", 
                agentName, 
                card.name(), 
                card.skills() != null ? card.skills().size() : 0);
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
        
        // 关闭所有连接池
        if (poolEnabled) {
            for (A2AClientPool pool : agentPools.values()) {
                pool.close();
            }
            agentPools.clear();
        }
        
        agentConfigs.clear();
        agentClients.clear();
        agentCards.clear();
        agentHealth.clear();
    }
}

