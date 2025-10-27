package com.google.a2a.client.aggregator;

import com.google.a2a.client.service.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Aggregator Agent
 * <p>
 * 负责增量聚合多个子 Agent 的 Token 流
 * 
 * 核心功能：
 * 1. 维护完整的输入状态（currentInputPrompt）
 * 2. 维护已生成输出前缀（generatedOutputPrefix）
 * 3. 每次新 Token 到达触发增量生成
 * 4. 使用 LLM 服务的 Prefix-Caching 优化
 */
@Component
public class AggregatorAgent {

    private static final Logger logger = LoggerFactory.getLogger(AggregatorAgent.class);

    // 存储当前 Aggregator 已经收到的所有子 Agent 的 Token
    private final AtomicReference<String> currentInputPrompt = new AtomicReference<>("");
    // 存储 Aggregator 已经生成的所有 Token (用于构建下一轮 Prompt 的上下文)
    private final AtomicReference<String> generatedOutputPrefix = new AtomicReference<>("");
    
    // LLM Service 用于调用大模型
    private final LLMService llmService;
    
    public AggregatorAgent(LLMService llmService) {
        this.llmService = llmService;
    }

    /**
     * 聚合的主方法
     * <p>
     * 将合并后的 Agent Token 流转换为聚合后的输出流
     * 
     * @param mergedAgentStream 合并后的 Agent Token 流
     * @param userQuery 用户原始查询
     * @return 聚合后的输出流（Flux<String>）
     */
    public Flux<String> aggregateAndStream(Flux<String> mergedAgentStream, String userQuery) {
        logger.info("Starting aggregation for user query: {}", userQuery);
        
        // 最终输出流 (用于推送到客户端)
        final Sinks.Many<String> finalOutputSink = Sinks.many().unicast().onBackpressureBuffer();

        // 批量处理输入的 Token（避免频繁触发 LLM 调用）
        mergedAgentStream
            .bufferTimeout(10, Duration.ofMillis(500)) // 每 500ms 或 10 个 token 触发一次
            .doOnNext(tokenBatch -> {
                // 1. 更新聚合器的输入状态
                String batchContent = String.join("", tokenBatch);
                String newInputChunk = parseTokenContent(batchContent);
                
                String updatedInput = currentInputPrompt.updateAndGet(current -> current + "\n" + newInputChunk);
                
                logger.debug("Received batch of {} tokens, input length: {}", 
                        tokenBatch.size(), updatedInput.length());

                // 2. 触发增量总结（模拟"阶梯式流"的逻辑）
                // 每次收到新 Token 都立即尝试生成 Aggregator 的输出 Token
                generateAggregatorTokens(updatedInput, userQuery, finalOutputSink);
            })
            .doOnComplete(() -> {
                // 所有输入流完成，发送最终信号
                logger.info("Input stream complete, finalizing aggregation");
                
                // 执行最终聚合
                performFinalAggregation(userQuery, currentInputPrompt.get(), finalOutputSink);
            })
            .doOnError(error -> {
                logger.error("Error in merged stream", error);
                finalOutputSink.tryEmitError(error);
            })
            .subscribe();

        // 返回最终输出流
        return finalOutputSink.asFlux();
    }
    
    /**
     * 模拟 LLM 调用，实现增量 Token 生成
     * <p>
     * 关键点：每次调用都发送完整的历史输入，依靠 LLM 服务的 Prefix-Caching 优化
     * 
     * @param fullInput 完整的输入历史
     * @param userQuery 用户原始查询
     * @param outputSink 输出 Sink
     */
    private void generateAggregatorTokens(
            String fullInput, 
            String userQuery,
            Sinks.Many<String> outputSink) {
        
        // 1. 构建 LLM 提示词 (Prompt)
        String systemPrompt = "你是一个专业的总结专家，擅长整合多个信息源的内容。\n" +
                              "请根据以下来自多个 Agent 的信息，生成一个统一、连贯、完整的答案。\n" +
                              "要求：1) 保留所有重要信息；2) 去除重复内容；3) 组织清晰；4) 语言自然流畅。";
        
        // 包含所有历史输入和当前 Aggregator 已经生成的输出作为上下文
        String promptWithContext = buildPromptWithContext(
                userQuery,
                fullInput,
                generatedOutputPrefix.get()
        );
        
        logger.debug("Generating incremental tokens, input length: {}, output prefix length: {}", 
                fullInput.length(), generatedOutputPrefix.get().length());

        // 2. 模拟调用 LLM API 并流式获取新 Token
        // 实际中会调用 OpenAIClient.chatStream() 或类似的 LLM SDK 方法
        // 这里使用 mock 实现
        Flux<String> newOutputTokens = mockLLMStreamCall(promptWithContext);

        // 3. 消费 LLM 的输出并推送到最终的 Sink
        newOutputTokens.subscribe(
            outputToken -> {
                // 更新 Aggregator 的已生成输出前缀
                generatedOutputPrefix.updateAndGet(prefix -> prefix + outputToken);
                // 推送给客户端
                outputSink.tryEmitNext(outputToken);
                
                logger.debug("Emitted token: {}", outputToken);
            },
            // 处理 LLM 调用错误
            error -> {
                logger.error("Error generating tokens", error);
                outputSink.tryEmitError(error);
            },
            // LLM 本次增量调用完成
            () -> {
                logger.debug("Incremental token generation complete");
            }
        );
    }
    
    /**
     * 构建包含上下文的完整 Prompt
     */
    private String buildPromptWithContext(String userQuery, String fullInput, String generatedOutput) {
        return String.format("""
                用户问题: %s
                
                来自子 Agent 的输入:
                %s
                
                当前正在生成的摘要:
                %s
                
                请基于上述信息继续生成统一的答案。
                """, userQuery, fullInput, generatedOutput);
    }
    
    /**
     * 执行最终聚合
     * <p>
     * 当所有输入流完成后，执行一次最终的聚合
     */
    private void performFinalAggregation(
            String userQuery,
            String fullInput,
            Sinks.Many<String> outputSink) {
        
        logger.info("Performing final aggregation with input length: {}", fullInput.length());
        
        String finalPrompt = buildFinalPrompt(userQuery, fullInput, generatedOutputPrefix.get());
        
        // 调用 LLM 生成最终响应
        Flux<String> finalResponse = mockLLMStreamCall(finalPrompt);
        
        finalResponse
                .doOnNext(token -> {
                    generatedOutputPrefix.updateAndGet(prefix -> prefix + token);
                    outputSink.tryEmitNext(token);
                })
                .doOnError(outputSink::tryEmitError)
                .doOnComplete(() -> outputSink.tryEmitComplete())
                .subscribe();
    }
    
    /**
     * 构建最终聚合的 Prompt
     */
    private String buildFinalPrompt(String userQuery, String fullInput, String generatedOutput) {
        return String.format("""
                用户问题: %s
                
                这是从所有子 Agent 收集的完整信息:
                
                %s
                
                当前已生成的回答:
                
                %s
                
                请完成最终的回答，确保信息完整、逻辑清晰、语言流畅。
                """, userQuery, fullInput, generatedOutput);
    }

    /**
     * 假设的 LLM 模拟调用
     * <p>
     * 在实际应用中，您会在这里调用一个真正的 LLM API 客户端
     * 并且该客户端必须支持流式响应 (Flux/Stream)
     * 并且 LLM 服务端必须支持 Prefix-Caching
     */
    private Flux<String> mockLLMStreamCall(String prompt) {
        // 模拟流式响应
        return Flux.just("基于", "最新", "输入", "的", "增量", "总结", "...", "\n")
                .delayElements(Duration.ofMillis(100));
    }

    /**
     * 假设的输入解析方法
     * <p>
     * 实际中需要 JSON 解析，获取 Token 和 Agent ID
     * 例如：{"agent":"A", "token":"..."} -> "A: ..."
     * 
     * @param incomingToken 接收到的 Token
     * @return 解析后的内容
     */
    private String parseTokenContent(String incomingToken) {
        // 这里简化处理，直接返回原始 Token
        // 实际应用中需要解析 JSON 格式
        return incomingToken;
    }
    
    /**
     * 重置聚合器状态
     * <p>
     * 用于新的会话开始时清空历史状态
     */
    public void reset() {
        currentInputPrompt.set("");
        generatedOutputPrefix.set("");
        logger.info("Aggregator reset");
    }
}

