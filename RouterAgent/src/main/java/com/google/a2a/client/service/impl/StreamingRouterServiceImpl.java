package com.google.a2a.client.service.impl;

import com.google.a2a.client.component.aggregator.AggregatorAgent;
import com.google.a2a.client.component.analyzer.IntentAnalyzer;
import com.google.a2a.client.listener.StreamingEventListener;
import com.google.a2a.client.manager.A2AClient;
import com.google.a2a.client.manager.AgentRegistry;
import com.google.a2a.client.model.IntentAnalysisResult;
import com.google.a2a.client.model.RoutedRequest;
import com.google.a2a.client.service.StreamingRouterService;
import com.travelassistant.common.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.BufferOverflowStrategy;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 流式路由服务实现
 * <p>
 * 整合了 IntentAnalyzer、AgentOrchestrator 和 AggregatorAgent
 * 核心流程：
 * 1. 意图分析，获取需要调用的 Agent 列表
 * 2. Fan-out: 并行调用所有 Agent 的流式接口
 * 3. Fan-in: 使用 Flux.merge() 合并所有流
 * 4. 增量聚合: 使用 AggregatorAgent 进行 Token 重新生成
 * 
 * 错误处理策略：
 * - 单个 Agent 失败不会影响其他 Agent 的执行
 * - 使用 onErrorResume 将失败的 Agent 替换为友好错误消息
 * - 确保合并流能够继续处理其他 Agent 的正常输出
 */
@Service
public class StreamingRouterServiceImpl implements StreamingRouterService {

    private static final Logger logger = LoggerFactory.getLogger(StreamingRouterServiceImpl.class);

    // 背压配置常量
    private static final int AGENT_STREAM_BUFFER_SIZE = 1000;  // Agent 流缓冲区大小
    private static final int MERGE_PREFETCH_SIZE = 256;  // 流合并预取大小

    private final IntentAnalyzer intentAnalyzer;
    private final AggregatorAgent aggregatorAgent;
    private final AgentRegistry agentRegistry;
    
    public StreamingRouterServiceImpl(
            IntentAnalyzer intentAnalyzer, 
            AggregatorAgent aggregatorAgent,
            AgentRegistry agentRegistry) {
        this.intentAnalyzer = intentAnalyzer;
        this.aggregatorAgent = aggregatorAgent;
        this.agentRegistry = agentRegistry;
    }

    /**
     * 流式路由主方法
     * 
     * @param request 路由请求
     * @return 流式 Token 流（Flux<String>）
     */
    @Override
    public Flux<String> routeStreaming(RoutedRequest request) {
        logger.info("[StreamingRouterService] Starting streaming route for request: {}", request.query());
        
        try {
            // 步骤 1: 分析用户意图，获取需要调用的 Agent 列表
            logger.info("[Step 1/3] Analyzing intent...");
            IntentAnalysisResult analysis = intentAnalyzer.analyze(request.query());
            
            if (analysis.selectedAgents().isEmpty()) {
                logger.warn("No agents selected for streaming");
                return Flux.just("抱歉，未找到合适的 Agent 处理您的请求。");
            }
            
            logger.info("[Step 1/3] Selected {} agents: {}", 
                    analysis.selectedAgents().size(), 
                    analysis.selectedAgents());
            
            // 步骤 2: 并行获取所有 Agent 的流，并添加背压控制
            logger.info("[Step 2/3] Fan-out: Fetching {} agent streams in parallel...", 
                    analysis.selectedAgents().size());
            
            List<Flux<String>> agentStreams = createAgentStreams(
                    analysis.selectedAgents(), 
                    analysis.splitQueries(), 
                    request.query());
            
            // 步骤 3: 合并所有流
            logger.info("[Step 3/3] Fan-in: Merging {} streams with backpressure control...", agentStreams.size());
            Flux<String> mergedStream = mergeStreams(agentStreams);
            
            // 步骤 4: 使用 AggregatorAgent 进行增量聚合
            logger.info("[Step 4] Incremental aggregation using AggregatorAgent...");
            return aggregatorAgent.aggregateAndStream(mergedStream, request.query());
            
        } catch (Exception e) {
            logger.error("Failed to create streaming route", e);
            return Flux.just("处理请求时出现错误: " + e.getMessage());
        }
    }

    /**
     * 创建 Agent 流列表（带背压控制和错误处理）
     */
    private List<Flux<String>> createAgentStreams(
            List<String> agentNames, 
            java.util.Map<String, String> splitQueries, 
            String defaultQuery) {
        
        return agentNames.stream()
                .map(agentName -> {
                    String query = splitQueries.getOrDefault(agentName, defaultQuery);
                    Flux<String> stream = fetchAgentStreamWithQuery(agentName, query);
                    return addBackpressureControl(stream, agentName);
                })
                .collect(Collectors.toList());
    }
    
    /**
     * 为流添加背压控制和错误处理
     * <p>
     * 关键特性：
     * 1. 背压控制：使用 onBackpressureBuffer 避免内存溢出
     * 2. 错误隔离：使用 onErrorResume 确保单个 Agent 失败不影响其他 Agent
     * 3. 友好降级：返回错误提示而非中断整个流程
     * 
     * @param stream 原始 Agent 流
     * @param agentName Agent 名称（用于日志和错误消息）
     * @return 包含背压控制和错误处理的流
     */
    private Flux<String> addBackpressureControl(Flux<String> stream, String agentName) {
        return stream
                .onBackpressureBuffer(
                        AGENT_STREAM_BUFFER_SIZE,
                        BufferOverflowStrategy.DROP_OLDEST
                )
                .doOnNext(token -> logger.trace("Agent {} token: {}", agentName, token))
                .doOnError(error -> logger.error("Agent {} stream error", agentName, error))
                // 关键：使用 onErrorResume 而非 onErrorReturn，确保单个 Agent 失败不中断 mergedStream
                .onErrorResume(error -> {
                    logger.warn("Agent {} stream failed, continuing with others. Error: {}", 
                            agentName, error.getMessage());
                    return Flux.just("[Agent " + agentName + " 暂时不可用]");
                })
                .doOnCancel(() -> logger.debug("Agent {} stream cancelled", agentName))
                .doOnComplete(() -> logger.debug("Agent {} stream completed successfully", agentName));
    }
    
    /**
     * 合并多个流
     */
    private Flux<String> mergeStreams(List<Flux<String>> streams) {
        if (streams.size() == 1) {
            return streams.get(0);
        }
        
        @SuppressWarnings("unchecked")
        Flux<String>[] streamsArray = streams.toArray(new Flux[0]);
        return Flux.merge(MERGE_PREFETCH_SIZE, streamsArray);
    }
    
    /**
     * 从单个 Agent 获取流（带专属查询）
     * 使用 A2A 协议标准方式调用 Agent
     */
    public Flux<String> fetchAgentStreamWithQuery(String agentName, String query) {
        return Flux.defer(() -> {
            logger.info("Fetching stream from agent '{}' with query: {}", agentName, query);
            
            A2AClient client = agentRegistry.getClient(agentName)
                    .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentName));
            
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            TaskSendParams params = createTaskParams(query);
            
            client.sendTaskStreaming(params, createStreamingEventListener(agentName, sink));
            
            return sink.asFlux();
        })
        .subscribeOn(Schedulers.boundedElastic());
    }
    
    /**
     * 创建流式事件监听器
     */
    private StreamingEventListener createStreamingEventListener(String agentName, Sinks.Many<String> sink) {
        return new StreamingEventListener() {
            @Override
            public void onEvent(Object event) {
                logger.debug("Received event from {}: {}", agentName, event.getClass().getSimpleName());
                
                String content = extractEventContent(event);
                if (content != null && !content.isEmpty()) {
                    logger.debug("Extracted content from {}: {}", agentName, content);
                    emitTokenSafely(sink, content, agentName);
                }
            }
            
            @Override
            public void onError(Exception exception) {
                logger.error("Error from agent {}: {}", agentName, exception.getMessage());
                sink.tryEmitNext("[Agent " + agentName + " 错误: " + exception.getMessage() + "]");
                sink.tryEmitComplete();
            }
            
            @Override
            public void onComplete() {
                logger.info("Stream completed from agent: {}", agentName);
                sink.tryEmitComplete();
            }
        };
    }
    
    /**
     * 安全地发送 Token（处理背压）
     */
    private void emitTokenSafely(Sinks.Many<String> sink, String token, String agentName) {
        Sinks.EmitResult result = sink.tryEmitNext(token);
        if (result.isFailure()) {
            logger.warn("Failed to emit token from {}: {} (backpressure)", agentName, result);
            if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
                logger.warn("Buffer overflow for agent {}, dropping token", agentName);
            }
        }
    }
    
    /**
     * 创建 A2A Task 参数
     */
    private TaskSendParams createTaskParams(String query) {
        // 创建文本 Part
        TextPart textPart = new TextPart(query);
        
        // 创建 Message
        String messageId = "msg-" + System.currentTimeMillis();
        Message message = new Message(messageId, "user", List.of(textPart));
        
        // 创建 TaskSendParams
        String taskId = "task-" + System.currentTimeMillis();
        return new TaskSendParams(
                taskId,      // id
                null,        // sessionId
                message,     // message
                null,        // pushNotification
                null,        // historyLength
                null         // metadata
        );
    }
    
    /**
     * 从事件对象中提取文本内容
     * 支持 TaskStatusUpdateEvent 和 TaskArtifactUpdateEvent
     */
    private String extractEventContent(Object event) {
        if (event == null) {
            return null;
        }
        
        try {
            if (event instanceof TaskArtifactUpdateEvent artifactEvent) {
                return extractTextFromArtifact(artifactEvent.artifact());
            }
            
            if (event instanceof TaskStatusUpdateEvent statusEvent) {
                return extractTextFromMessage(statusEvent.status().message());
            }
            
            logger.debug("Unknown event type: {}, content: {}", 
                    event.getClass().getSimpleName(), event);
            return null;
            
        } catch (Exception e) {
            logger.warn("Failed to extract content from event: {}", event.getClass().getSimpleName(), e);
            return null;
        }
    }
    
    /**
     * 从 Artifact 中提取文本内容
     */
    private String extractTextFromArtifact(Artifact artifact) {
        if (artifact == null || artifact.parts() == null) {
            return null;
        }
        
        return extractTextFromParts(artifact.parts());
    }
    
    /**
     * 从 Message 中提取文本内容
     */
    private String extractTextFromMessage(Message message) {
        if (message == null || message.parts() == null) {
            return null;
        }
        
        return extractTextFromParts(message.parts());
    }
    
    /**
     * 从 Part 列表中提取文本内容
     */
    private String extractTextFromParts(List<Part> parts) {
        if (parts == null) {
            return null;
        }
        
        StringBuilder content = new StringBuilder();
        for (Part part : parts) {
            if (part instanceof TextPart textPart && textPart.text() != null) {
                content.append(textPart.text());
            }
        }
        
        return content.length() > 0 ? content.toString() : null;
    }
}

