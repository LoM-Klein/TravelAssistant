package com.google.a2a.client.manager.monitor;

import com.google.a2a.client.manager.AgentDiscovery;
import com.google.a2a.client.manager.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Agent 健康监控器
 * <p>
 * 定期检查 Agent 的健康状态，记录指标
 */
public class AgentHealthMonitor {
    
    private static final Logger logger = LoggerFactory.getLogger(AgentHealthMonitor.class);
    private static final int DEFAULT_CHECK_INTERVAL = 30; // 秒
    
    private final AgentRegistry registry;
    private final AgentDiscovery discovery;
    private final Map<String, AgentMetrics> metricsMap;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> monitoringTask;
    private volatile boolean running;
    
    public AgentHealthMonitor(AgentRegistry registry, AgentDiscovery discovery) {
        this.registry = registry;
        this.discovery = discovery;
        this.metricsMap = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.running = false;
    }
    
    /**
     * 启动健康监控
     */
    public synchronized void start() {
        if (running) {
            logger.warn("Health monitor is already running");
            return;
        }
        
        logger.info("Starting agent health monitoring");
        running = true;
        
        monitoringTask = scheduler.scheduleAtFixedRate(
                this::performHealthCheck,
                0,
                DEFAULT_CHECK_INTERVAL,
                TimeUnit.SECONDS
        );
        
        logger.info("Health monitoring started with interval: {} seconds", DEFAULT_CHECK_INTERVAL);
    }
    
    /**
     * 停止健康监控
     */
    public synchronized void stop() {
        if (!running) {
            logger.warn("Health monitor is not running");
            return;
        }
        
        logger.info("Stopping agent health monitoring");
        running = false;
        
        if (monitoringTask != null) {
            monitoringTask.cancel(false);
        }
        
        logger.info("Health monitoring stopped");
    }
    
    /**
     * 执行健康检查
     */
    private void performHealthCheck() {
        logger.debug("Performing health check for all agents");
        
        for (String agentName : registry.getAllAgentNames()) {
            try {
                boolean healthy = discovery.validateAgent(agentName);
                
                // 更新指标
                AgentMetrics metrics = metricsMap.computeIfAbsent(
                        agentName,
                        AgentMetrics::new
                );
                
                if (healthy) {
                    metrics.recordSuccess(0); // 健康检查不记录响应时间
                    logger.debug("Agent {} is healthy", agentName);
                } else {
                    metrics.recordFailure();
                    logger.warn("Agent {} is unhealthy", agentName);
                }
                
                metrics.setHealthy(healthy);
                
            } catch (Exception e) {
                logger.error("Health check failed for agent: {}", agentName, e);
                registry.updateHealth(agentName, false);
                
                AgentMetrics metrics = metricsMap.computeIfAbsent(
                        agentName,
                        AgentMetrics::new
                );
                metrics.recordFailure();
                metrics.setHealthy(false);
            }
        }
    }
    
    /**
     * 获取 Agent 指标
     */
    public AgentMetrics getMetrics(String agentName) {
        return metricsMap.get(agentName);
    }
    
    /**
     * 获取所有 Agent 指标
     */
    public Map<String, AgentMetrics> getAllMetrics() {
        return new ConcurrentHashMap<>(metricsMap);
    }
    
    /**
     * 打印所有 Agent 的健康状态
     */
    public void printHealthStatus() {
        logger.info("=== Agent Health Status ===");
        
        for (Map.Entry<String, AgentMetrics> entry : metricsMap.entrySet()) {
            logger.info("{}", entry.getValue());
        }
        
        logger.info("===========================");
    }
    
    /**
     * 关闭监控器
     */
    public void shutdown() {
        stop();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

