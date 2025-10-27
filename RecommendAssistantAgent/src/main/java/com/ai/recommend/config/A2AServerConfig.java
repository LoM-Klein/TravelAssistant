package com.ai.recommend.config;

import com.ai.recommend.Service.RecommendAssistant;
import com.ai.recommend.Service.Impl.A2ARecommendTaskHandler;
import com.travelassistant.common.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.common.model.*;
import com.ai.recommend.registry.A2AServer;
import com.ai.recommend.Service.TaskHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * A2A Server 配置类 - 旅行推荐助手
 * 
 * 将 RecommendAssistant 服务适配到 A2A Server 架构
 */
@Configuration
public class A2AServerConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(A2AServerConfig.class);
    
    @Value("${server.port:8080}")
    private int serverPort;
    
    @Value("${a2a.agent.name:Travel Recommendation Assistant}")
    private String agentName;
    
    @Value("${a2a.agent.description:Intelligent travel recommendation assistant powered by RAG technology}")
    private String agentDescription;

    /**
     * 配置 A2A Server Bean
     */
    @Bean
    public A2AServer a2aServer(
            ObjectMapper objectMapper, 
            RecommendAssistant recommendAssistant) {
        
        logger.info("初始化 A2A Server for Travel Recommendation Assistant");
        
        // 创建旅行推荐代理卡片
        AgentCard agentCard = createTravelRecommendAgentCard();
        
        // 创建任务处理器
        TaskHandler taskHandler = new A2ARecommendTaskHandler(recommendAssistant);
        
        return new A2AServer(agentCard, taskHandler, objectMapper);
    }

    /**
     * 创建旅行推荐代理卡片
     * 描述 Agent 的能力和特性
     */
    private AgentCard createTravelRecommendAgentCard() {
        
        // 提供商信息
        AgentProvider provider = new AgentProvider(
            "AI Travel Team",
            "https://travel-assistant.example.com"
        );

        // 代理能力
        AgentCapabilities capabilities = new AgentCapabilities(
            true,  // 支持流式传输
            true,  // 支持推送通知
            true   // 支持状态转换历史
        );

        // 认证方式
        AgentAuthentication authentication = new AgentAuthentication(
            List.of("bearer", "none"),  // 支持的认证类型
            null
        );

        // 定义代理技能
        AgentSkill travelRecommendSkill = new AgentSkill(
            "travel-recommendation",
            "Travel Recommendation Service",
            "Intelligent travel recommendation assistant that provides personalized travel suggestions using advanced RAG (Retrieval-Augmented Generation) technology. " +
            "Features include: intent recognition, parallel multi-category search, context-aware recommendations, and conversation memory.",
            List.of(
                "travel", 
                "recommendation", 
                "rag", 
                "intelligent-search", 
                "trip-planning",
                "tourism",
                "attractions",
                "hotels",
                "restaurants"
            ),
            List.of(
                "Example: 推荐一些北京的必去景点",
                "Example: 我想去杭州旅游，有什么好的酒店推荐？",
                "Example: 上海有哪些特色美食？",
                "Example: 帮我规划一个三天的成都旅游行程"
            ),
            List.of("text"),  // 输入类型
            List.of("text")   // 输出类型
        );

        // 构建完整的代理卡片
        return new AgentCard(
            agentName,
            agentDescription,
            "http://localhost:" + serverPort + "/a2a",
            provider,
            "1.0.0",
            "http://localhost:" + serverPort + "/docs",
            capabilities,
            authentication,
            List.of("text"),  // 支持的输入模态
            List.of("text"),  // 支持的输出模态
            List.of(travelRecommendSkill)
        );
    }
}

