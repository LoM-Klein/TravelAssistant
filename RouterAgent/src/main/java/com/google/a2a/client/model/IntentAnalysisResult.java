package com.google.a2a.client.model;

import java.util.List;
import java.util.Map;

/**
 * 意图分析结果
 * <p>
 * 大模型分析用户意图后的结果
 *
 * @param intents 识别的意图列表（如：["translation", "travel", "weather"]）
 * @param selectedAgents 选中的 Agent 名称列表
 * @param splitQueries 拆分的查询列表（每个 Agent 对应的具体查询）
 * @param reasoning 分析推理过程
 * @param confidence 置信度 (0.0-1.0)
 */
public record IntentAnalysisResult(
    List<String> intents,
    List<String> selectedAgents,
    Map<String, String> splitQueries,  // agentName -> query
    String reasoning,
    double confidence
) {
    /**
     * 检查是否需要多个 Agent
     */
    public boolean isMultiAgent() {
        return selectedAgents != null && selectedAgents.size() > 1;
    }
    
    /**
     * 获取单个 Agent（如果只需要一个）
     */
    public String getSingleAgent() {
        if (selectedAgents != null && !selectedAgents.isEmpty()) {
            return selectedAgents.get(0);
        }
        return null;
    }
}

