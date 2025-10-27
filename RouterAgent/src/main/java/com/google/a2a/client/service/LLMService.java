package com.google.a2a.client.service;

/**
 * 大模型服务接口
 * <p>
 * 用于意图分析的大模型服务
 * 可以集成 Spring AI、OpenAI、或其他 LLM 服务
 */
public interface LLMService {
    
    /**
     * 分析用户查询
     *
     * @param prompt 分析提示词
     * @return 大模型响应（JSON 格式）
     */
    String analyze(String prompt);
    
    /**
     * 聚合多个 Agent 的响应
     *
     * @param userQuery 用户原始查询
     * @param agentResponses Agent 响应列表（agentName -> response）
     * @return 聚合后的最终响应
     */
    String aggregate(String userQuery, java.util.Map<String, String> agentResponses);
}

