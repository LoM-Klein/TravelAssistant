package com.google.a2a.client.service;

import reactor.core.publisher.Flux;

/**
 * 大模型服务接口
 * <p>
 * 用于意图分析和响应聚合的大模型服务
 * 可以集成 Spring AI、OpenAI、或其他 LLM 服务
 */
public interface LLMService {
    
    /**
     * 分析用户查询意图（同步）
     * <p>
     * 用于路由决策，需要快速返回结果
     *
     * @param userQuery 分析提示词
     * @return 大模型响应（JSON 格式）
     */
    String analyze(String userQuery, String agentDescriptions);
    
    /**
     * 流式聚合 Agent 的响应
     * <p>
     * 将一个或多个 Agent 的输出整合成连贯的回答
     * 
     * @param userQuery 用户原始查询
     * @param agentOutput 从 Agent 收集的输出内容（可能包含多个 Agent 的响应）
     * @return 聚合后的流式响应（Flux<String>）
     */
    Flux<String> aggregateStreaming(String userQuery, String agentOutput);
}

