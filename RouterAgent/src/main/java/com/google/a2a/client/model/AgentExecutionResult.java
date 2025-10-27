package com.google.a2a.client.model;

import com.travelassistant.common.model.Task;

/**
 * Agent 执行结果
 * <p>
 * 单个 Agent 的执行结果
 *
 * @param agentName Agent 名称
 * @param query 执行的查询
 * @param task 任务结果
 * @param responseText 提取的响应文本
 * @param success 是否成功
 * @param errorMessage 错误信息（如果失败）
 * @param executionTimeMs 执行时间（毫秒）
 */
public record AgentExecutionResult(
    String agentName,
    String query,
    Task task,
    String responseText,
    boolean success,
    String errorMessage,
    long executionTimeMs
) {
    /**
     * 创建成功的结果
     */
    public static AgentExecutionResult success(
            String agentName,
            String query,
            Task task,
            String responseText,
            long executionTimeMs) {
        return new AgentExecutionResult(
            agentName,
            query,
            task,
            responseText,
            true,
            null,
            executionTimeMs
        );
    }
    
    /**
     * 创建失败的结果
     */
    public static AgentExecutionResult failure(
            String agentName,
            String query,
            String errorMessage,
            long executionTimeMs) {
        return new AgentExecutionResult(
            agentName,
            query,
            null,
            null,
            false,
            errorMessage,
            executionTimeMs
        );
    }
}

