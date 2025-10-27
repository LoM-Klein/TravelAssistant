package com.ai.recommend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Java Agent配置类
 * 配置聊天记忆和重试机制
 *
 * @author Travel Agent
 * @since 1.0.0
 */
@Configuration
@EnableRetry // 启用Spring Retry
public class JavaAgentConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(JavaAgentConfig.class);
    
    /**
     * 聊天记忆配置
     * 存储多轮对话历史，实现上下文感知
     *
     * @return 聊天记忆实例
     */
    @Bean
    public ChatMemory chatMemory() {
        logger.info("初始化聊天记忆...");
        return MessageWindowChatMemory.builder()
                .maxMessages(50) // 存储最近50条对话记录
                .build();
    }
}
