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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregator Agent - 聚合多个 Agent 的输出流
 * <p>
 * 🎯 核心流程（简化版）：
 * <pre>
 * 1. 接收多个 Agent 的 Token 流（带 [AgentName] 标签）
 * 2. 批量收集 Tokens（减少 LLM 调用）
 * 3. 解析并按 Agent 分组整理
 * 4. 调用 LLM 进行增量聚合
 * 5. 流式输出聚合结果给前端
 * </pre>
 * 
 * 📊 状态管理：
 * - 每次请求创建独立的状态（线程安全）
 * - currentInput: 累积的 Agent 输入
 * - generatedOutput: 已生成的聚合输出
 * - isGenerating: 是否正在调用 LLM
 * 
 * 🔥 性能优化（针对 RPM 15000）：
 * - 批处理大小：50 tokens
 * - 批处理超时：1500ms
 * - 输入缓冲区：8000 tokens
 */
@Component
public class AggregatorAgent {

    private static final Logger logger = LoggerFactory.getLogger(AggregatorAgent.class);
    private static final Pattern AGENT_TAG_PATTERN = Pattern.compile("\\[([^\\]]+)\\]([^\\[]+)");

    private final LLMService llmService;
    
    // 配置参数
    private static final int BATCH_SIZE = 50;
    private static final Duration BATCH_TIMEOUT = Duration.ofMillis(1500);
    private static final int INPUT_BUFFER_SIZE = 8000;
    
    public AggregatorAgent(LLMService llmService) {
        this.llmService = llmService;
    }

    /**
     * 聚合的主方法
     * 
     * @param mergedAgentStream 合并后的 Agent Token 流（格式：[AgentName]content）
     * @param userQuery 用户原始查询
     * @return 聚合后的输出流
     */
    public Flux<String> aggregateAndStream(Flux<String> mergedAgentStream, String userQuery) {
        logger.info("🚀 开始聚合处理，用户问题: {}", userQuery);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 📦 步骤 1: 初始化状态（每次请求独立）
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AtomicReference<String> currentInput = new AtomicReference<>("");      // 累积的 Agent 输入
        AtomicReference<String> generatedOutput = new AtomicReference<>("");   // 已生成的聚合输出
        AtomicBoolean isGenerating = new AtomicBoolean(false);                 // 是否正在调用 LLM
        Sinks.Many<String> outputSink = Sinks.many().unicast().onBackpressureBuffer();

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 📥 步骤 2: 批量接收 Agent Tokens
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        mergedAgentStream
            .onBackpressureBuffer(INPUT_BUFFER_SIZE, BufferOverflowStrategy.DROP_OLDEST)
            .onErrorContinue((error, token) -> 
                logger.error("处理 token 出错，跳过: {}", token, error))
            .bufferTimeout(BATCH_SIZE, BATCH_TIMEOUT)  // 批量收集
            .doOnNext(batch -> 
                processAndGenerate(batch, currentInput, generatedOutput, isGenerating, userQuery, outputSink))
            .doOnComplete(() -> 
                finalizeLLMOutput(currentInput.get(), generatedOutput.get(), userQuery, outputSink))
            .doOnError(error -> {
                logger.error("❌ Agent 流异常", error);
                outputSink.tryEmitError(error);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 📤 步骤 3: 返回输出流
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        return outputSink.asFlux()
                .doOnCancel(() -> logger.warn("⚠️ 客户端取消订阅"))
                .doOnTerminate(() -> logger.info("✅ 聚合流结束"));
    }
    
    /**
     * 📦 处理批次 + 调用 LLM 生成聚合输出
     * <p>
     * 合并了原来的 processTokenBatch + triggerGenerationIfIdle + generateAggregatorTokens
     */
    private void processAndGenerate(
            List<String> batch,
            AtomicReference<String> currentInput,
            AtomicReference<String> generatedOutput,
            AtomicBoolean isGenerating,
            String userQuery,
            Sinks.Many<String> outputSink) {
        
        if (batch.isEmpty() || isGenerating.get()) {
            return;  // 批次为空或正在生成，直接跳过
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 1️⃣ 解析并更新输入状态
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        String batchContent = String.join("", batch);
        Map<String, StringBuilder> agentOutputs = parseAgentTags(batchContent);
        
        String updatedInput = currentInput.updateAndGet(current -> {
            StringBuilder result = new StringBuilder(current);
            for (Map.Entry<String, StringBuilder> entry : agentOutputs.entrySet()) {
                String agentName = entry.getKey();
                String content = entry.getValue().toString().trim();
                if (content.isEmpty()) continue;
                
                String marker = "【" + agentName + "】";
                if (result.indexOf(marker) < 0) {
                    // 新 Agent，创建段落
                    if (!result.isEmpty()) result.append("\n\n");
                    result.append(marker).append("\n");
                }
                result.append(content);
            }
            return result.toString();
        });
        
        logger.debug("📦 批次: {} tokens, {} agents, 总输入: {} 字符", 
                batch.size(), agentOutputs.size(), updatedInput.length());
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 2️⃣ 调用 LLM 进行增量聚合（CAS 保证只有一个线程调用）
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        if (!isGenerating.compareAndSet(false, true)) {
            return;  // 其他线程正在生成，跳过
        }
        
        String currentPrefix = generatedOutput.get();
        logger.debug("🤖 调用 LLM 聚合: 输入 {} 字符, 已生成 {} 字符", 
                updatedInput.length(), currentPrefix.length());
        
        llmService.aggregateStreaming(userQuery, updatedInput, currentPrefix)
                .doOnNext(token -> {
                    generatedOutput.updateAndGet(prefix -> prefix + token);
                    tryEmit(outputSink, token);
                })
                .doOnError(error -> {
                    logger.error("❌ LLM 聚合失败", error);
                    isGenerating.set(false);
                })
                .doOnComplete(() -> {
                    logger.debug("✅ 增量聚合完成");
                    isGenerating.set(false);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }
    
    /**
     * 📤 最终化输出（Agent 流结束后调用）
     * <p>
     * 简化版的 performFinalAggregation
     */
    private void finalizeLLMOutput(
            String fullInput,
            String generatedOutput,
            String userQuery,
            Sinks.Many<String> outputSink) {
        
        logger.info("🏁 最终化聚合: 输入 {} 字符, 已生成 {} 字符", 
                fullInput.length(), generatedOutput.length());
        
        // 如果已经生成了内容，直接结束
        if (!generatedOutput.isEmpty()) {
            outputSink.tryEmitComplete();
            return;
        }
        
        // 如果没有输入，返回降级消息
        if (fullInput.trim().isEmpty()) {
            tryEmit(outputSink, "暂无可用的回答内容");
            outputSink.tryEmitComplete();
            return;
        }
        
        // 最后一次尝试聚合
        llmService.aggregateStreaming(userQuery, fullInput, "")
                .doOnNext(token -> tryEmit(outputSink, token))
                .doOnError(error -> {
                    logger.error("❌ 最终聚合失败，降级到原始输出", error);
                    tryEmit(outputSink, "\n⚠️ 聚合服务异常，以下是原始回复：\n\n");
                    tryEmit(outputSink, fullInput);
                    outputSink.tryEmitComplete();
                })
                .doOnComplete(outputSink::tryEmitComplete)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }
    
    /**
     * 🏷️ 解析 Agent 标签
     * <p>
     * 输入: "[AgentA]内容A[AgentB]内容B"
     * 输出: {"AgentA": "内容A", "AgentB": "内容B"}
     */
    private Map<String, StringBuilder> parseAgentTags(String content) {
        Map<String, StringBuilder> result = new LinkedHashMap<>();
        Matcher matcher = AGENT_TAG_PATTERN.matcher(content);
        
        while (matcher.find()) {
            String agentName = matcher.group(1);
            String agentContent = matcher.group(2);
            result.computeIfAbsent(agentName, k -> new StringBuilder()).append(agentContent);
        }
        
        // 无法解析时，标记为未知 Agent
        if (result.isEmpty() && !content.trim().isEmpty()) {
            logger.warn("⚠️ 无法解析 Agent 标签: {}", content.substring(0, Math.min(50, content.length())));
            result.put("UnknownAgent", new StringBuilder(content));
        }
        
        return result;
    }
    
    /**
     * 📤 安全发送 Token
     */
    private void tryEmit(Sinks.Many<String> sink, String token) {
        Sinks.EmitResult result = sink.tryEmitNext(token);
        if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
            logger.warn("⚠️ 输出缓冲区溢出，丢弃 token");
        }
    }
}

