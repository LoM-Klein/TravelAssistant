package com.google.a2a.client.service.impl;

import com.google.a2a.client.component.analyzer.IntentAnalyzer;
import com.google.a2a.client.model.*;
import com.google.a2a.client.component.orchestrator.AgentOrchestrator;
import com.google.a2a.client.service.LLMService;
import com.google.a2a.client.service.RouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 路由服务
 * <p>
 * 核心路由逻辑：
 * 1. 分析用户意图
 * 2. 拆分查询
 * 3. 并行调度 Agent
 * 4. 聚合响应
 */
public class RouterServiceImpl implements RouterService {
    
    private static final Logger logger = LoggerFactory.getLogger(RouterServiceImpl.class);
    
    private final IntentAnalyzer intentAnalyzer;
    private final AgentOrchestrator orchestrator;
    private final LLMService llmService;
    
    public RouterServiceImpl(
            IntentAnalyzer intentAnalyzer,
            AgentOrchestrator orchestrator,
            LLMService llmService) {
        this.intentAnalyzer = intentAnalyzer;
        this.orchestrator = orchestrator;
        this.llmService = llmService;
    }
    
    /**
     * 路由请求到合适的 Agent
     * <p>
     * 完整的路由流程：
     * 1. 分析用户意图
     * 2. 拆分查询为多个子问题
     * 3. 并行执行选中的 Agent
     * 4. 聚合多个 Agent 的响应
     * 5. 返回最终结果
     */
    @Override
    public RoutedResponse route(RoutedRequest request) {
        logger.info("[RouterService] Routing request: {} - {}", request.requestId(), request.query());
        long startTime = System.currentTimeMillis();
        
        try {
            // ========== 步骤 1: 分析用户意图 ==========
            logger.info("[Step 1/3] Analyzing user intent...");
            IntentAnalysisResult analysis = intentAnalyzer.analyze(request.query());
            
            logger.info("[Step 1/3] Intent analysis complete: {} intents, {} agents selected, confidence: {}",
                    analysis.intents().size(),
                    analysis.selectedAgents().size(),
                    String.format("%.2f", analysis.confidence()));
            
            // 如果没有选中任何 Agent
            if (analysis.selectedAgents().isEmpty()) {
                logger.warn("[Step 1/3] No agents selected for query: {}", request.query());
                return createEmptyResponse(request.requestId(), startTime);
            }
            
            // 记录分析结果
            logger.info("[Step 1/3] Selected agents: {}", analysis.selectedAgents());
            logger.info("[Step 1/3] Split queries: {}", analysis.splitQueries());
            logger.info("[Step 1/3] Reasoning: {}", analysis.reasoning());
            
            // ========== 步骤 2: 并行执行 Agent 任务 ==========
            logger.info("[Step 2/3] Executing {} agents in parallel...", 
                    analysis.selectedAgents().size());
            
            List<AgentExecutionResult> agentResults = orchestrator.executeParallel(
                    analysis.splitQueries(),
                    request.contextId()
            );
            
            // 统计执行结果
            long successfulCount = agentResults.stream()
                    .filter(AgentExecutionResult::success)
                    .count();
            long failedCount = agentResults.size() - successfulCount;
            
            logger.info("[Step 2/3] Agent execution complete: {}/{} successful, {} failed",
                    successfulCount, agentResults.size(), failedCount);
            
            // ========== 步骤 3: 聚合响应 ==========
            logger.info("[Step 3/3] Aggregating responses...");
            String finalResponse = aggregateResponses(
                    request.query(),
                    agentResults
            );
            
            logger.info("[Step 3/3] Response aggregation complete");
            
            // ========== 步骤 4: 构建元数据 ==========
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("intents", analysis.intents());
            metadata.put("selectedAgents", analysis.selectedAgents());
            metadata.put("splitQueries", analysis.splitQueries());
            metadata.put("reasoning", analysis.reasoning());
            metadata.put("confidence", analysis.confidence());
            metadata.put("successfulAgents", successfulCount);
            metadata.put("failedAgents", failedCount);
            metadata.put("totalAgents", agentResults.size());
            
            // 记录每个 Agent 的执行情况
            List<Map<String, Object>> agentExecutionDetails = agentResults.stream()
                    .map(result -> Map.<String, Object>of(
                            "agentName", result.agentName(),
                            "success", result.success(),
                            "executionTimeMs", result.executionTimeMs(),
                            "error", result.errorMessage() != null ? result.errorMessage() : ""
                    ))
                    .toList();
            metadata.put("agentExecutionDetails", agentExecutionDetails);
            
            long totalTime = System.currentTimeMillis() - startTime;
            logger.info("[RouterService] Request routing complete in {}ms", totalTime);
            
            // ========== 步骤 4: 返回最终响应 ==========
            return new RoutedResponse(
                    request.requestId(),
                    finalResponse,
                    agentResults,
                    totalTime,
                    metadata
            );
            
        } catch (Exception e) {
            logger.error("[RouterService] Failed to route request", e);
            
            // 返回错误响应
            return new RoutedResponse(
                    request.requestId(),
                    "抱歉，处理您的请求时出现错误：" + e.getMessage() + 
                    "。请检查输入或稍后再试。",
                    Collections.emptyList(),
                    System.currentTimeMillis() - startTime,
                    Map.of(
                            "error", e.getMessage(),
                            "errorType", e.getClass().getSimpleName(),
                            "timestamp", System.currentTimeMillis()
                    )
            );
        }
    }
    
    /**
     * 创建空响应（当没有选中任何 Agent 时）
     */
    private RoutedResponse createEmptyResponse(String requestId, long startTime) {
        String emptyMessage = """
            抱歉，无法为您的查询找到合适的 Agent 来处理。
            
            可能的原因：
            1. 查询内容不够明确
            2. 暂无可用的 Agent 具备相应的能力
            3. 所有相关 Agent 目前不在线
            
            建议：
            - 尝试更具体的描述您的需求
            - 检查可用的 Agent 状态
            """;
        
        return new RoutedResponse(
                requestId,
                emptyMessage,
                Collections.emptyList(),
                System.currentTimeMillis() - startTime,
                Map.of(
                        "reason", "no agents selected",
                        "timestamp", System.currentTimeMillis()
                )
        );
    }
    
    /**
     * 聚合多个 Agent 的响应
     */
    private String aggregateResponses(String userQuery, List<AgentExecutionResult> agentResults) {
        // 过滤成功的结果
        List<AgentExecutionResult> successfulResults = agentResults.stream()
                .filter(AgentExecutionResult::success)
                .toList();
        
        if (successfulResults.isEmpty()) {
            return "抱歉，所有代理执行都失败了。请稍后再试。";
        }
        
        // 如果只有一个成功的结果，直接返回
        if (successfulResults.size() == 1) {
            return successfulResults.get(0).responseText();
        }
        
        // 使用大模型聚合多个响应
        Map<String, String> agentResponses = successfulResults.stream()
                .collect(Collectors.toMap(
                        AgentExecutionResult::agentName,
                        AgentExecutionResult::responseText
                ));
        
        return llmService.aggregate(userQuery, agentResponses);
    }
}

