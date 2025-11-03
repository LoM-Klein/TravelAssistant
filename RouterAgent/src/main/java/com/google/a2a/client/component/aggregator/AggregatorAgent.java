package com.google.a2a.client.component.aggregator;

import com.google.a2a.client.service.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.BufferOverflowStrategy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Aggregator Agent
 * <p>
 * 负责增量聚合多个子 Agent 的 Token 流
 * <p>
 * 核心功能：
 * 1. 维护完整的输入状态（currentInputPrompt）
 * 2. 维护已生成输出前缀（generatedOutputPrefix）
 * 3. 每次新 Token 到达触发增量生成
 * 4. 使用 LLM 服务的流式接口进行真正的聚合
 * <p>
 * 改进点：
 * - 每次请求创建独立的状态，避免并发问题
 * - 使用真正的 LLMService 而非 mock
 * - 添加并发控制和错误处理
 * - 优化批处理策略
 * <p>
 * 状态管理说明：
 * - 本类是单例 @Component，但线程安全
 * - aggregateAndStream() 方法为每次请求创建独立的局部状态变量
 * - 多个并发请求不会共享状态，完全隔离
 * - 状态变量包括：currentInputPrompt, generatedOutputPrefix, isGenerating 等
 */
@Component

public class AggregatorAgent {

    private static final Logger logger = LoggerFactory.getLogger(AggregatorAgent.class);

    // LLM Service 用于调用大模型
    private final LLMService llmService;
    
    // 配置参数
    private static final int BATCH_SIZE = 20;  // 每批处理的 token 数量
    private static final Duration BATCH_TIMEOUT = Duration.ofMillis(800);  // 批处理超时时间
    private static final int INPUT_BUFFER_SIZE = 5000;  // 输入流缓冲区大小
    
    public AggregatorAgent(LLMService llmService) {
        this.llmService = llmService;
    }

    /**
     * 聚合的主方法
     * <p>
     * 将合并后的 Agent Token 流转换为聚合后的输出流
     * 
     * @param mergedAgentStream 合并后的 Agent Token 流（已包含单个 Agent 的错误处理）
     * @param userQuery 用户原始查询
     * @return 聚合后的输出流（Flux<String>），包含监控钩子
     */
    public Flux<String> aggregateAndStream(Flux<String> mergedAgentStream, String userQuery) {
        logger.info("Starting aggregation for user query: {}", userQuery);
        
        // 为每次请求创建独立的状态（避免并发问题）
        // 注意：即使 AggregatorAgent 是单例，这些状态变量是方法局部的，完全隔离
        final AtomicReference<String> currentInputPrompt = new AtomicReference<>("");
        final AtomicReference<String> generatedOutputPrefix = new AtomicReference<>("");
        final AtomicBoolean isGenerating = new AtomicBoolean(false);
        final ReentrantLock generationLock = new ReentrantLock();
        
        // 最终输出流 (用于推送到客户端)
        final Sinks.Many<String> finalOutputSink = createOutputSink();

        // 批量处理输入的 Token（避免频繁触发 LLM 调用）
        mergedAgentStream
            .onBackpressureBuffer(INPUT_BUFFER_SIZE, BufferOverflowStrategy.DROP_OLDEST)
            .doOnNext(token -> logger.trace("Received token in aggregator: {}", token))
            // 二次错误处理：即使单个 Agent 已处理错误，这里再加一层保险
            .onErrorContinue((error, token) -> logger.error("Error processing token in aggregator, continuing: {}", token, error))
            .bufferTimeout(BATCH_SIZE, BATCH_TIMEOUT)
            .doOnNext(tokenBatch -> processTokenBatch(
                    tokenBatch, 
                    currentInputPrompt, 
                    userQuery, 
                    generatedOutputPrefix, 
                    finalOutputSink, 
                    isGenerating, 
                    generationLock))
            .doOnComplete(() -> {
                logger.info("Input stream complete, finalizing aggregation");
                performFinalAggregation(
                        userQuery, 
                        currentInputPrompt.get(), 
                        generatedOutputPrefix.get(),
                        finalOutputSink);
            })
            .doOnError(error -> {
                logger.error("Error in merged stream", error);
                finalOutputSink.tryEmitError(error);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();

        // 返回最终输出流，添加监控钩子
        return finalOutputSink.asFlux()
                .doOnRequest(n -> logger.debug("Client requested {} tokens from aggregator output", n))
                .doOnCancel(() -> logger.warn("Client cancelled aggregator output stream (backpressure signal or user abort)"))
                .doOnTerminate(() -> logger.info("Aggregator output stream terminated"))
                .doOnError(error -> logger.error("Error in aggregator output stream", error));
    }
    
    /**
     * 处理 Token 批次
     */
    private void processTokenBatch(
            List<String> tokenBatch,
            AtomicReference<String> currentInputPrompt,
            String userQuery,
            AtomicReference<String> generatedOutputPrefix,
            Sinks.Many<String> outputSink,
            AtomicBoolean isGenerating,
            ReentrantLock generationLock) {
        
        if (tokenBatch.isEmpty()) {
            return;
        }
        
        // 更新聚合器的输入状态
        String batchContent = String.join("", tokenBatch);
        String newInputChunk = parseTokenContent(batchContent);
        String updatedInput = currentInputPrompt.updateAndGet(current -> 
                current.isEmpty() ? newInputChunk : current + "\n" + newInputChunk);
        
        logger.debug("Received batch of {} tokens, total input length: {}", 
                tokenBatch.size(), updatedInput.length());

        // 触发增量聚合（使用锁避免并发调用）
        triggerGenerationIfIdle(
                updatedInput, 
                userQuery, 
                generatedOutputPrefix, 
                outputSink, 
                isGenerating, 
                generationLock);
    }
    
    /**
     * 如果当前未生成，则触发生成（线程安全）
     */
    private void triggerGenerationIfIdle(
            String fullInput,
            String userQuery,
            AtomicReference<String> generatedOutputPrefix,
            Sinks.Many<String> outputSink,
            AtomicBoolean isGenerating,
            ReentrantLock generationLock) {
        
        if (isGenerating.get()) {
            return;
        }
        
        generationLock.lock();
        try {
            if (!isGenerating.get()) {
                isGenerating.set(true);
                generateAggregatorTokens(
                        fullInput, 
                        userQuery, 
                        generatedOutputPrefix,
                        outputSink,
                        isGenerating);
            }
        } finally {
            generationLock.unlock();
        }
    }
    
    /**
     * 增量生成聚合 Token
     * <p>
     * 当收集到足够的 Agent 输出后，调用 LLM 进行聚合
     */
    private void generateAggregatorTokens(
            String fullInput, 
            String userQuery,
            AtomicReference<String> generatedOutputPrefix,
            Sinks.Many<String> outputSink,
            AtomicBoolean isGenerating) {
        
        logger.debug("Generating aggregated tokens, input length: {}", fullInput.length());

        Flux<String> newOutputTokens = llmService.aggregateStreaming(userQuery, fullInput);

        subscribeToLLMOutput(
                newOutputTokens, 
                generatedOutputPrefix, 
                outputSink, 
                isGenerating);
    }
    
    /**
     * 订阅 LLM 输出流并处理 Token
     */
    private void subscribeToLLMOutput(
            Flux<String> outputTokens,
            AtomicReference<String> generatedOutputPrefix,
            Sinks.Many<String> outputSink,
            AtomicBoolean isGenerating) {
        
        outputTokens
                .doOnNext(token -> {
                    generatedOutputPrefix.updateAndGet(prefix -> prefix + token);
                    emitTokenSafely(outputSink, token, "aggregated");
                })
                .doOnError(error -> {
                    logger.error("Error generating aggregated tokens", error);
                    isGenerating.set(false);
                })
                .doOnComplete(() -> {
                    logger.debug("Incremental token generation complete");
                    isGenerating.set(false);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }
    
    /**
     * 执行最终聚合
     * <p>
     * 当所有输入流完成后，对收集到的完整内容进行最终聚合
     */
    private void performFinalAggregation(
            String userQuery,
            String fullInput,
            String generatedPrefix,
            Sinks.Many<String> outputSink) {
        
        logger.info("Performing final aggregation with input length: {}", fullInput.length());
        
        // 如果已经有部分生成的内容，说明增量聚合已经在进行，直接完成即可
        if (generatedPrefix != null && !generatedPrefix.isEmpty()) {
            logger.info("Already have generated content (length: {}), completing stream", generatedPrefix.length());
            outputSink.tryEmitComplete();
            return;
        }
        
        // 如果没有输入内容，直接完成
        if (fullInput.trim().isEmpty()) {
            logger.warn("No input content for final aggregation");
            emitTokenSafely(outputSink, "暂无可用的回答内容", "fallback");
            outputSink.tryEmitComplete();
            return;
        }
        
        try {
            Flux<String> finalResponse = llmService.aggregateStreaming(userQuery, fullInput);
            
            finalResponse
                    .doOnNext(token -> emitTokenSafely(outputSink, token, "final"))
                    .doOnError(error -> {
                        logger.error("Error in final aggregation", error);
                        // 降级：返回原始 Agent 输出
                        logger.warn("LLM aggregation failed, using raw agent output as fallback");
                        emitTokenSafely(outputSink, "\n[聚合服务暂时不可用，以下是原始回复]\n\n", "fallback");
                        emitTokenSafely(outputSink, fullInput, "fallback");
                        outputSink.tryEmitComplete();
                    })
                    .doOnComplete(() -> {
                        logger.info("Final aggregation complete");
                        outputSink.tryEmitComplete();
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
                    
        } catch (Exception e) {
            logger.error("Failed to perform final aggregation", e);
            // 降级处理
            logger.warn("Using raw agent output as fallback due to exception");
            emitTokenSafely(outputSink, "\n[聚合服务异常，以下是原始回复]\n\n", "fallback");
            emitTokenSafely(outputSink, fullInput, "fallback");
            outputSink.tryEmitComplete();
        }
    }
    
    /**
     * 安全地发送 Token（处理背压）
     */
    private void emitTokenSafely(Sinks.Many<String> sink, String token, String type) {
        Sinks.EmitResult result = sink.tryEmitNext(token);
        if (result.isFailure()) {
            logger.warn("Failed to emit {} token: {} (backpressure)", type, result);
            if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
                logger.warn("Output buffer overflow, dropping token");
            }
        } else {
            logger.debug("Emitted {} token: {}", type, token);
        }
    }
    
    /**
     * 创建输出 Sink
     */
    private Sinks.Many<String> createOutputSink() {
        return Sinks.many().unicast().onBackpressureBuffer();
    }

    /**
     * 解析 Token 内容
     * <p>
     * 从 Agent 返回的 Token 中提取实际内容
     * 当前实现：直接返回原始 Token
     * 未来可以扩展支持 JSON 格式或其他结构化格式
     * 
     * @param incomingToken 接收到的 Token
     * @return 解析后的内容
     */
    private String parseTokenContent(String incomingToken) {
        if (incomingToken == null || incomingToken.trim().isEmpty()) {
            return "";
        }
        
        // 当前简化处理：直接返回原始 Token
        // 实际应用中可以：
        // 1. 解析 JSON 格式：{"agent":"A", "token":"..."} -> "A: ..."
        // 2. 去除控制字符
        // 3. 格式化内容
        
        return incomingToken.trim();
    }
    
    /**
     * 重置聚合器状态（已废弃）
     * <p>
     * 注意：现在状态是按请求管理的，不需要全局重置
     * 保留此方法以保持向后兼容性
     * 
     * @deprecated 状态现在按请求管理，不需要全局重置
     */
    @Deprecated
    public void reset() {
        logger.warn("reset() method is deprecated. State is now managed per-request.");
    }
}

