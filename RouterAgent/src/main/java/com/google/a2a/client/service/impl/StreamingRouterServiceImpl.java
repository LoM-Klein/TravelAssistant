package com.google.a2a.client.service.impl;

import com.google.a2a.client.component.aggregator.AggregatorAgent;
import com.google.a2a.client.component.analyzer.IntentAnalyzer;
import com.google.a2a.client.listener.StreamingEventListener;
import com.google.a2a.client.manager.A2AClient;
import com.google.a2a.client.manager.AgentRegistry;
import com.google.a2a.client.model.IntentAnalysisResult;
import com.google.a2a.client.model.RoutedRequest;
import com.google.a2a.client.service.StreamingRouterService;
import com.google.a2a.client.strategy.AdaptiveMergeStrategy;
import com.travelassistant.common.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>
 * 错误处理策略：
 * - 单个 Agent 失败不会影响其他 Agent 的执行
 * - 使用 onErrorResume 将失败的 Agent 替换为友好错误消息
 * - 确保合并流能够继续处理其他 Agent 的正常输出
 */
@Service
public class StreamingRouterServiceImpl implements StreamingRouterService {

    private static final Logger logger = LoggerFactory.getLogger(StreamingRouterServiceImpl.class);

    // 背压配置常量（🔥 已针对 RPM 15000 优化）
    private static final int AGENT_STREAM_BUFFER_SIZE = 2000;  // 🔥 优化：从 1000 增加到 2000，防止快速 Agent 溢出
    private static final int MERGE_PREFETCH_SIZE = 512;  // 🔥 优化：从 256 增加到 512，提升合并吞吐

    private final IntentAnalyzer intentAnalyzer;
    private final AggregatorAgent aggregatorAgent;
    private final AgentRegistry agentRegistry;
    private final AdaptiveMergeStrategy adaptiveMergeStrategy;
    
    // 是否启用自适应合并策略
    @Value("${router.adaptive-merge.enabled:false}")
    private boolean adaptiveMergeEnabled;
    
    public StreamingRouterServiceImpl(
            IntentAnalyzer intentAnalyzer, 
            AggregatorAgent aggregatorAgent,
            AgentRegistry agentRegistry) {
        this.intentAnalyzer = intentAnalyzer;
        this.aggregatorAgent = aggregatorAgent;
        this.agentRegistry = agentRegistry;
        this.adaptiveMergeStrategy = new AdaptiveMergeStrategy();
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
            logger.info("[Step 3/3] Fan-in: Merging {} streams with {} strategy...", 
                    agentStreams.size(),
                    adaptiveMergeEnabled ? "adaptive priority" : "standard");
            Flux<String> mergedStream = mergeStreams(agentStreams, analysis.selectedAgents());
            
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
     * 
     * 🔥 改进：为每个token添加Agent标签，解决乱序合并问题
     */
    private List<Flux<String>> createAgentStreams(
            List<String> agentNames, 
            java.util.Map<String, String> splitQueries, 
            String defaultQuery) {
        
        return agentNames.stream()
                .map(agentName -> {
                    String query = splitQueries.getOrDefault(agentName, defaultQuery);
                    Flux<String> stream = fetchAgentStreamWithQuery(agentName, query);
                    
                    // 🔥 关键改进：为每个token添加Agent标签
                    // 格式：[AgentName]token内容
                    // 这样聚合器就能知道每段内容来自哪个Agent
                    Flux<String> taggedStream = stream
                            .map(token -> String.format("[%s]%s", agentName, token));
                    
                    return addBackpressureControl(taggedStream, agentName);
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
     * <p>
     * 🔥 支持两种合并策略：
     * - 标准合并：使用 Flux.merge()，先到先处理
     * - 自适应合并：根据缓冲区状态动态调整优先级
     * 
     * @param streams Agent 流列表
     * @param agentNames Agent 名称列表
     * @return 合并后的流
     */
    private Flux<String> mergeStreams(List<Flux<String>> streams, List<String> agentNames) {
        if (streams.size() == 1) {
            return streams.get(0);
        }
        
        // 根据配置选择合并策略
        if (adaptiveMergeEnabled) {
            logger.info("🔥 Using adaptive merge strategy with priority control");
            return adaptiveMergeStrategy.mergeWithAdaptivePriority(streams, agentNames);
        } else {
            logger.info("Using standard merge strategy");
            @SuppressWarnings("unchecked")
            Flux<String>[] streamsArray = streams.toArray(new Flux[0]);
            return Flux.merge(MERGE_PREFETCH_SIZE, streamsArray);
        }
    }
    
    /**
     * 从单个 Agent 获取流（带专属查询）
     * <p>
     * 使用 A2A 协议标准方式调用 Agent
     * <p>
     * 🔥 连接池支持：
     * - 如果启用连接池，自动借用和归还客户端
     * - 流完成时自动归还连接
     * - 流出错时自动标记无效连接（如果是连接问题）
     */
    public Flux<String> fetchAgentStreamWithQuery(String agentName, String query) {
        return Flux.defer(() -> {
            logger.info("Fetching stream from agent '{}' with query: {}", agentName, query);
            
            // 🔥 优化：根据是否启用连接池选择不同的获取方式
            A2AClient client;
            boolean usePool = agentRegistry.isPoolEnabled();
            
            if (usePool) {
                // 从连接池借用客户端
                client = agentRegistry.borrowClient(agentName);
                logger.debug("Borrowed client from pool for agent '{}'", agentName);
            } else {
                // 传统方式：直接获取客户端
                client = agentRegistry.getClient(agentName)
                        .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentName));
            }
            
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            TaskSendParams params = createTaskParams(query);
            
            client.sendTaskStreaming(params, createStreamingEventListener(agentName, sink));
            
            Flux<String> resultFlux = sink.asFlux();
            
            // 🔥 关键：如果使用连接池，流完成时自动归还连接
            if (usePool) {
                resultFlux = resultFlux
                        .doFinally(signalType -> {
                            logger.debug("Stream from agent '{}' finished with signal: {}, returning client to pool", 
                                    agentName, signalType);
                            agentRegistry.returnClient(agentName, client);
                        })
                        .doOnError(error -> {
                            // 判断是否是连接问题，如果是则标记连接无效
                            if (isConnectionError(error)) {
                                logger.error("Connection error detected for agent '{}', invalidating client", agentName);
                                agentRegistry.invalidateClient(agentName, client);
                            }
                        });
            }
            
            return resultFlux;
        })
        .subscribeOn(Schedulers.boundedElastic());
    }
    
    /**
     * 判断是否是连接错误
     * 
     * @param error 异常
     * @return true 如果是连接相关的错误
     */
    private boolean isConnectionError(Throwable error) {
        if (error == null) {
            return false;
        }
        
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        
        // 检查常见的连接错误关键字
        return message.toLowerCase().contains("connection") ||
               message.toLowerCase().contains("timeout") ||
               message.toLowerCase().contains("refused") ||
               message.toLowerCase().contains("unreachable") ||
               error instanceof java.net.ConnectException ||
               error instanceof java.net.SocketTimeoutException;
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
        
        return !content.isEmpty() ? content.toString() : null;
    }
}

