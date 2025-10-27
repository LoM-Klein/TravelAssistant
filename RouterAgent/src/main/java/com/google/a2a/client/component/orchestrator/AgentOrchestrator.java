package com.google.a2a.client.component.orchestrator;

import com.google.a2a.client.Exception.A2AClientException;
import com.google.a2a.client.manager.A2AClient;
import com.google.a2a.client.manager.AgentManager;
import com.google.a2a.client.model.AgentExecutionResult;
import com.travelassistant.common.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Agent 编排器
 * <p>
 * 并行调度多个 Agent 执行任务
 */
public class AgentOrchestrator {
    
    private static final Logger logger = LoggerFactory.getLogger(AgentOrchestrator.class);
    private final AgentManager agentManager;
    private final ExecutorService executorService;
    
    public AgentOrchestrator(AgentManager agentManager) {
        this.agentManager = agentManager;
        this.executorService = Executors.newCachedThreadPool();
    }
    
    /**
     * 并行执行多个 Agent 任务
     *
     * @param splitQueries Agent 名称到查询的映射
     * @param contextId 会话 ID
     * @return Agent 执行结果列表
     */
    public List<AgentExecutionResult> executeParallel(
            Map<String, String> splitQueries,
            String contextId) {
        
        logger.info("Executing {} agents in parallel", splitQueries.size());
        
        List<CompletableFuture<AgentExecutionResult>> futures = new ArrayList<>();
        
        // 为每个 Agent 创建异步任务
        for (Map.Entry<String, String> entry : splitQueries.entrySet()) {
            String agentName = entry.getKey();
            String query = entry.getValue();
            
            CompletableFuture<AgentExecutionResult> future = CompletableFuture.supplyAsync(
                () -> executeAgent(agentName, query, contextId),
                executorService
            );
            
            futures.add(future);
        }
        
        // 等待所有任务完成
        try {
            CompletableFuture<Void> allOf = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );
            
            // 设置超时时间（60秒）
            allOf.get(60, TimeUnit.SECONDS);
            
            // 收集所有结果
            List<AgentExecutionResult> results = new ArrayList<>();
            for (CompletableFuture<AgentExecutionResult> future : futures) {
                results.add(future.get());
            }
            
            logger.info("All {} agents completed execution", results.size());
            return results;
            
        } catch (TimeoutException e) {
            logger.error("Agent execution timeout", e);
            // 收集已完成的结果
            return collectCompletedResults(futures);
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Agent execution failed", e);
            return collectCompletedResults(futures);
        }
    }
    
    /**
     * 执行单个 Agent
     */
    private AgentExecutionResult executeAgent(String agentName, String query, String contextId) {
        long startTime = System.currentTimeMillis();
        
        logger.info("Executing agent: {} with query: {}", agentName, query);
        
        try {
            // 1. 获取 Agent 客户端
            Optional<A2AClient> clientOpt = agentManager.getAgent(agentName);
            
            if (clientOpt.isEmpty()) {
                return AgentExecutionResult.failure(
                    agentName,
                    query,
                    "Agent not found: " + agentName,
                    System.currentTimeMillis() - startTime
                );
            }
            
            A2AClient client = clientOpt.get();
            
            // 2. 构建任务参数
            TaskSendParams params = buildTaskParams(query, contextId);
            
            // 3. 发送任务
            JSONRPCResponse response = client.sendTask(params);
            
            // 4. 检查响应
            if (response.error() != null) {
                return AgentExecutionResult.failure(
                    agentName,
                    query,
                    "Agent returned error: " + response.error().message(),
                    System.currentTimeMillis() - startTime
                );
            }
            
            // 5. 提取响应
            Task task = (Task) response.result();
            String responseText = extractResponseText(task);
            
            long executionTime = System.currentTimeMillis() - startTime;
            logger.info("Agent {} completed in {}ms", agentName, executionTime);
            
            return AgentExecutionResult.success(
                agentName,
                query,
                task,
                responseText,
                executionTime
            );
            
        } catch (A2AClientException e) {
            logger.error("Agent {} execution failed: {}", agentName, e.getMessage());
            return AgentExecutionResult.failure(
                agentName,
                query,
                "Execution error: " + e.getMessage(),
                System.currentTimeMillis() - startTime
            );
        }
    }
    
    /**
     * 构建任务参数
     */
    private TaskSendParams buildTaskParams(String query, String contextId) {
        String taskId = "task-" + UUID.randomUUID().toString();
        String messageId = "msg-" + UUID.randomUUID().toString();
        
        TextPart textPart = new TextPart(query, null);
        Message message = new Message(
            messageId,
            "message",
            "user",
            List.of(textPart),
            contextId,
            null,
            null,
            null
        );
        
        return new TaskSendParams(
            taskId,
            null,
            message,
            null,
            null,
            Map.of()
        );
    }
    
    /**
     * 从任务中提取响应文本
     */
    private String extractResponseText(Task task) {
        if (task == null || task.history() == null || task.history().isEmpty()) {
            return "";
        }
        
        // 获取最后一条助手消息
        for (int i = task.history().size() - 1; i >= 0; i--) {
            Message msg = task.history().get(i);
            if ("assistant".equals(msg.role()) && msg.parts() != null) {
                for (Part part : msg.parts()) {
                    if (part instanceof TextPart textPart) {
                        return textPart.text();
                    }
                }
            }
        }
        
        return "";
    }
    
    /**
     * 收集已完成的结果
     */
    private List<AgentExecutionResult> collectCompletedResults(
            List<CompletableFuture<AgentExecutionResult>> futures) {
        
        List<AgentExecutionResult> results = new ArrayList<>();
        for (CompletableFuture<AgentExecutionResult> future : futures) {
            if (future.isDone() && !future.isCancelled()) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    logger.error("Failed to get result from completed future", e);
                }
            }
        }
        return results;
    }
    
    /**
     * 关闭编排器
     */
    public void shutdown() {
        logger.info("Shutting down orchestrator");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

