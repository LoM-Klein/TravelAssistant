package com.google.a2a.client.config;

import java.util.List;
import java.util.Map;

/**
 * Agent 配置类
 * <p>
 * 定义单个 Agent 的配置信息，包括连接地址、能力描述、优先级等
 *
 * @param name Agent 名称（唯一标识）
 * @param url Agent 服务地址
 * @param description Agent 描述
 * @param skills Agent 技能列表
 * @param enabled 是否启用
 * @param priority 优先级（数字越大优先级越高）
 * @param timeout 超时时间（毫秒）
 * @param retryAttempts 重试次数
 * @param healthCheckInterval 健康检查间隔（秒）
 * @param metadata 额外的元数据
 */
public record AgentConfig(
    String name,
    String url,
    String description,
    List<String> skills,
    boolean enabled,
    int priority,
    long timeout,
    int retryAttempts,
    int healthCheckInterval,
    Map<String, Object> metadata
) {
    
    /**
     * 创建默认配置的 Builder
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Agent 配置构建器
     */
    public static class Builder {
        private String name;
        private String url;
        private String description = "";
        private List<String> skills = List.of();
        private boolean enabled = true;
        private int priority = 0;
        private long timeout = 30000; // 30秒
        private int retryAttempts = 3;
        private int healthCheckInterval = 60; // 60秒
        private Map<String, Object> metadata = Map.of();
        
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        public Builder url(String url) {
            this.url = url;
            return this;
        }
        
        public Builder description(String description) {
            this.description = description;
            return this;
        }
        
        public Builder skills(List<String> skills) {
            this.skills = skills;
            return this;
        }
        
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }
        
        public Builder timeout(long timeout) {
            this.timeout = timeout;
            return this;
        }
        
        public Builder retryAttempts(int retryAttempts) {
            this.retryAttempts = retryAttempts;
            return this;
        }
        
        public Builder healthCheckInterval(int healthCheckInterval) {
            this.healthCheckInterval = healthCheckInterval;
            return this;
        }
        
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }
        
        public AgentConfig build() {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Agent name cannot be null or empty");
            }
            if (url == null || url.trim().isEmpty()) {
                throw new IllegalArgumentException("Agent URL cannot be null or empty");
            }
            
            return new AgentConfig(
                name, url, description, skills, enabled,
                priority, timeout, retryAttempts, healthCheckInterval, metadata
            );
        }
    }
    
    /**
     * 检查 Agent 是否具有指定技能
     */
    public boolean hasSkill(String skill) {
        return skills != null && skills.stream()
                .anyMatch(s -> s.equalsIgnoreCase(skill));
    }
    
    /**
     * 检查 Agent 是否具有任一指定技能
     */
    public boolean hasAnySkill(List<String> requiredSkills) {
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            return true;
        }
        return requiredSkills.stream().anyMatch(this::hasSkill);
    }
    
    /**
     * 检查 Agent 是否具有所有指定技能
     */
    public boolean hasAllSkills(List<String> requiredSkills) {
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            return true;
        }
        return requiredSkills.stream().allMatch(this::hasSkill);
    }
}

