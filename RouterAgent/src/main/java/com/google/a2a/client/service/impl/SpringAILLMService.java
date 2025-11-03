package com.google.a2a.client.service.impl;

import com.google.a2a.client.service.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDate;

/**
 * 基于 Spring AI 的大模型服务实现
 * <p>
 * 使用 Spring AI 的 ChatClient 调用阿里云 DashScope 进行意图分析和响应聚合
 */
@Service
public class SpringAILLMService implements LLMService {
    
    private static final Logger logger = LoggerFactory.getLogger(SpringAILLMService.class);
    
    private final ChatClient chatClient;
    private final ChatModel chatModel;  // 保留 ChatModel 引用用于流式调用
    
    /**
     * 构造函数，注入 ChatModel
     * 
     * @param chatModel Spring AI 的 ChatModel (自动装配)
     */
    public SpringAILLMService(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.chatClient = ChatClient.builder(chatModel).build();
        logger.info("SpringAILLMService initialized with ChatModel");
    }
    
    /**
     * 分析用户查询意图（同步）
     * <p>
     * 用于路由决策，需要快速返回结果
     * 
     * @param userQuery 分析提示词
     * @return 大模型响应（JSON 格式）
     */
    @Override
    public String analyze(String userQuery, String agentDescriptions) {
        logger.info("Analyzing user intent with LLM...");
        String prompt = buildAnalysisPrompt(userQuery,agentDescriptions);
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
     * 流式聚合 Agent 的响应
     * <p>
     * 将从 Agent 收集的输出整合成连贯、自然的回答
     * 
     * @param userQuery 用户原始查询
     * @param agentOutput 从 Agent 收集的输出内容
     * @return 聚合后的流式响应（Flux<String>）
     */
    @Override
    public Flux<String> aggregateStreaming(String userQuery, String agentOutput) {
        logger.info("Starting streaming aggregation for query: {}, input length: {}", 
                userQuery, agentOutput != null ? agentOutput.length() : 0);
        
        // 如果没有 Agent 输出，直接返回空
        if (agentOutput == null || agentOutput.trim().isEmpty()) {
            logger.warn("No agent output to aggregate");
            return Flux.just("暂无可用的回答内容");
        }
        
        try {
            // 构建聚合提示词
            String aggregationPrompt = buildAggregationPrompt(userQuery, agentOutput);
            
            // 使用 ChatModel 的流式接口
            Prompt prompt = new Prompt(aggregationPrompt);
            Flux<ChatResponse> responseFlux = chatModel.stream(prompt);
            
            // 转换为字符串流
            return responseFlux
                    .map(response -> {
                        if (response.getResult() != null && response.getResult().getOutput() != null) {
                            String text = response.getResult().getOutput().getText();
                            return text != null ? text : "";
                        }
                        return "";
                    })
                    .filter(content -> !content.isEmpty())
                    .doOnError(error -> logger.error("Error in streaming aggregation", error))
                    .doOnComplete(() -> logger.debug("Streaming aggregation completed"));
            
        } catch (Exception e) {
            logger.error("Failed to start streaming aggregation", e);
            // 降级策略：直接返回原始内容
            return Flux.just(agentOutput);
        }
    }
    
    /**
     * 构建聚合提示词
     * <p>
     * 根据用户查询和 Agent 输出构建合适的提示词
     * 
     * @param userQuery 用户原始查询
     * @param agentOutput Agent 输出内容
     * @return 聚合提示词
     */
    private String buildAggregationPrompt(String userQuery, String agentOutput) {
        return String.format("""
                用户的问题是："%s"
                
                以下是专业 Agent 针对这个问题给出的响应内容：
                
                %s
                
                ---
                
                请你作为一个智能助手，将上述内容整理成一个清晰、连贯、完整的答案。
                
                要求：
                1. 保留所有重要信息和细节，不要遗漏关键内容
                2. 合理组织内容结构，使答案层次分明、易于阅读
                3. 去除明显重复的内容，但保留不同角度的补充信息
                4. 使用自然流畅的语言，让回答读起来像是一个整体而非拼凑
                5. 如果内容包含多个主题或分类，请用清晰的标题或段落分隔
                6. 保持专业性和准确性，不要添加未提及的信息
                
                请直接返回整理后的答案，不要添加"根据上述内容"等引导性语句。
                """, userQuery, agentOutput);
    }
    /**
     * 构建分析提示词
     * 参考 TravelIntentAnalyzer 的提示词设计，但更加通用
     */
    private String buildAnalysisPrompt(String userQuery, String agentDescriptions) {
        return String.format("""
            你是一个智能的 Agent 路由器，负责分析用户的查询意图并选择最合适的 Agent 来处理。
            
            用户查询: "%s"
            
            可用的 Agent 列表:
            %s
            
            请按照以下步骤进行分析:
            1. 识别用户的意图和需求
            2. 判断需要哪些 Agent 来处理这个查询（可以是一个或多个）
            3. 如果需要多个 Agent，请为每个 Agent 拆分出专门的子查询
            4. 说明为什么选择这些 Agent
            5. 评估你的分析置信度
            
            **重要规则**:
            - 如果查询涉及多个领域/技能，选择多个相关的 Agent
            - 为每个 Agent 生成针对性的查询，而不是简单地复制原始查询
            - 优先选择技能最匹配的 Agent
            - 如果不确定，选择通用能力强的 Agent
            
            请严格按照以下 JSON 格式返回（只返回 JSON，不要有其他内容）:
            {
              "intents": ["意图1", "意图2"],
              "selectedAgents": ["agentName1", "agentName2"],
              "splitQueries": {
                "agentName1": "针对 agent1 的专门查询",
                "agentName2": "针对 agent2 的专门查询"
              },
              "reasoning": "选择这些 Agent 的原因",
              "confidence": 0.95
            }
            
            JSON 响应:
            """, userQuery, agentDescriptions);
    }
}
