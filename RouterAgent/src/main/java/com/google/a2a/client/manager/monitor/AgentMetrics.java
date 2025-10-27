package com.google.a2a.client.manager.monitor;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 指标
 * <p>
 * 记录 Agent 的运行指标和统计信息
 */
public class AgentMetrics {
    
    private final String agentName;
    private final AtomicInteger totalRequests;
    private final AtomicInteger successfulRequests;
    private final AtomicInteger failedRequests;
    private final AtomicLong totalResponseTime;
    private volatile Instant lastRequestTime;
    private volatile Instant lastSuccessTime;
    private volatile Instant lastFailureTime;
    private volatile boolean isHealthy;
    
    public AgentMetrics(String agentName) {
        this.agentName = agentName;
        this.totalRequests = new AtomicInteger(0);
        this.successfulRequests = new AtomicInteger(0);
        this.failedRequests = new AtomicInteger(0);
        this.totalResponseTime = new AtomicLong(0);
        this.isHealthy = true;
    }
    
    /**
     * 记录成功请求
     */
    public void recordSuccess(long responseTimeMs) {
        totalRequests.incrementAndGet();
        successfulRequests.incrementAndGet();
        totalResponseTime.addAndGet(responseTimeMs);
        lastRequestTime = Instant.now();
        lastSuccessTime = Instant.now();
        isHealthy = true;
    }
    
    /**
     * 记录失败请求
     */
    public void recordFailure() {
        totalRequests.incrementAndGet();
        failedRequests.incrementAndGet();
        lastRequestTime = Instant.now();
        lastFailureTime = Instant.now();
    }
    
    /**
     * 更新健康状态
     */
    public void setHealthy(boolean healthy) {
        this.isHealthy = healthy;
    }
    
    /**
     * 获取成功率
     */
    public double getSuccessRate() {
        int total = totalRequests.get();
        if (total == 0) {
            return 1.0;
        }
        return (double) successfulRequests.get() / total;
    }
    
    /**
     * 获取平均响应时间
     */
    public double getAverageResponseTime() {
        int successful = successfulRequests.get();
        if (successful == 0) {
            return 0.0;
        }
        return (double) totalResponseTime.get() / successful;
    }
    
    // Getters
    public String getAgentName() {
        return agentName;
    }
    
    public int getTotalRequests() {
        return totalRequests.get();
    }
    
    public int getSuccessfulRequests() {
        return successfulRequests.get();
    }
    
    public int getFailedRequests() {
        return failedRequests.get();
    }
    
    public Instant getLastRequestTime() {
        return lastRequestTime;
    }
    
    public Instant getLastSuccessTime() {
        return lastSuccessTime;
    }
    
    public Instant getLastFailureTime() {
        return lastFailureTime;
    }
    
    public boolean isHealthy() {
        return isHealthy;
    }
    
    /**
     * 重置指标
     */
    public void reset() {
        totalRequests.set(0);
        successfulRequests.set(0);
        failedRequests.set(0);
        totalResponseTime.set(0);
        lastRequestTime = null;
        lastSuccessTime = null;
        lastFailureTime = null;
    }
    
    @Override
    public String toString() {
        return String.format(
                "AgentMetrics[agent=%s, total=%d, success=%d, failed=%d, successRate=%.2f%%, avgResponseTime=%.2fms, healthy=%s]",
                agentName,
                getTotalRequests(),
                getSuccessfulRequests(),
                getFailedRequests(),
                getSuccessRate() * 100,
                getAverageResponseTime(),
                isHealthy
        );
    }
}

