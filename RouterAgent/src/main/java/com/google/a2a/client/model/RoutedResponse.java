package com.google.a2a.client.model;

import java.util.List;
import java.util.Map;

/**
 * 路由响应
 * <p>
 * 路由 Agent 返回给用户的最终响应
 *
 * @param requestId 请求 ID
 * @param finalResponse 整合后的最终响应
 * @param agentResults Agent 执行结果列表
 * @param totalExecutionTimeMs 总执行时间（毫秒）
 * @param metadata 元数据
 */
public record RoutedResponse(
    String requestId,
    String finalResponse,
    List<AgentExecutionResult> agentResults,
    long totalExecutionTimeMs,
    Map<String, Object> metadata
) {
    /**
     * 获取成功的 Agent 数量
     */
    public int getSuccessfulAgentsCount() {
        if (agentResults == null) return 0;
        return (int) agentResults.stream()
                .filter(AgentExecutionResult::success)
                .count();
    }
    
    /**
     * 检查是否所有 Agent 都成功
     */
    public boolean isAllAgentsSuccessful() {
        if (agentResults == null || agentResults.isEmpty()) return false;
        return agentResults.stream()
                .allMatch(AgentExecutionResult::success);
    }
}

