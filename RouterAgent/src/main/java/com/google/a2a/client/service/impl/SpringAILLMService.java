package com.google.a2a.client.service.impl;

import com.google.a2a.client.service.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于 Spring AI 的大模型服务实现
 * <p>
 * 使用 Spring AI 的 ChatClient 调用阿里云 DashScope 进行意图分析和响应聚合
 */
@Service
public class SpringAILLMService implements LLMService {
    
    private static final Logger logger = LoggerFactory.getLogger(SpringAILLMService.class);
    
    private final ChatClient chatClient;
    
    /**
     * 构造函数，注入 ChatModel
     * 
     * @param chatModel Spring AI 的 ChatModel (自动装配)
     */
    public SpringAILLMService(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
        logger.info("SpringAILLMService initialized with ChatModel");
    }
    
    /**
     * 分析用户查询意图
     * 
     * @param prompt 分析提示词
     * @return 大模型响应（JSON 格式）
     */
    @Override
    public String analyze(String prompt) {
        logger.info("Analyzing user intent with LLM...");
        
        try {
            String response = chatClient.prompt()
                    .user(prompt)
                    .system(s -> s.param("current_date", LocalDate.now().toString()))
                    .call()
                    .content();
            
            logger.debug("LLM analysis response: {}", response);
            return response;
            
        } catch (Exception e) {
            logger.error("Failed to analyze intent with LLM", e);
            // 返回空的 JSON，让调用方使用降级策略
            return "{}";
        }
    }
    
    /**
     * 聚合多个 Agent 的响应
     * 
     * @param userQuery 用户原始查询
     * @param agentResponses Agent 响应列表（agentName -> response）
     * @return 聚合后的最终响应
     */
    @Override
    public String aggregate(String userQuery, Map<String, String> agentResponses) {
        logger.info("Aggregating {} agent responses for query: {}", 
                agentResponses.size(), userQuery);
        
        try {
            // 构建聚合提示词
            String agentResponsesText = agentResponses.entrySet().stream()
                    .map(entry -> String.format("### %s 的响应:\n%s", entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining("\n\n"));
            
            String aggregationPrompt = String.format("""
                用户的原始问题是："%s"
                
                以下是多个专业 Agent 针对这个问题给出的响应：
                
                %s
                
                请你作为一个智能助手，将以上多个 Agent 的响应整合成一个连贯、完整且自然的答案。
                
                要求：
                1. 保留所有重要信息，不要遗漏
                2. 合理组织内容结构，使答案更易读
                3. 去除重复内容
                4. 如果不同 Agent 的信息有矛盾，请优先选择更专业的回答
                5. 用自然流畅的语言呈现，不要让用户感觉是多个片段拼接的
                
                请直接返回整合后的答案，不要添加额外的解释。
                """, userQuery, agentResponsesText);
            
            String aggregatedResponse = chatClient.prompt()
                    .user(aggregationPrompt)
                    .system("你是一个专业的AI助手，擅长整合多个信息源的内容，为用户提供清晰、准确、完整的答案。")
                    .call()
                    .content();
            
            logger.info("Successfully aggregated agent responses");
            return aggregatedResponse;
            
        } catch (Exception e) {
            logger.error("Failed to aggregate agent responses with LLM", e);
            
            // 降级策略：简单拼接
            return agentResponses.values().stream()
                    .collect(Collectors.joining("\n\n---\n\n"));
        }
    }
}

