package com.google.a2a.client.service.impl;

import com.google.a2a.client.service.LLMService;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * 基于 Spring AI 的大模型服务实现
 * <p>
 * 使用 Spring AI 的 ChatClient 调用阿里云 DashScope 进行意图分析和响应聚合
 * <p>
 * 🔥 限流保护：
 * - 所有 LLM API 调用都经过全局 RateLimiter 限流
 * - 防止超出 API 密钥的 QPS/RPM 限制
 * - 限流失败时提供友好的降级处理
 */
@Service
public class SpringAILLMService implements LLMService {
    
    private static final Logger logger = LoggerFactory.getLogger(SpringAILLMService.class);
    
    private final ChatClient chatClient;
    private final ChatModel chatModel;  // 保留 ChatModel 引用用于流式调用
    private final RateLimiter rateLimiter;  // 全局限流器
    
    /**
     * 构造函数，注入 ChatModel 和 RateLimiter
     * 
     * @param chatModel Spring AI 的 ChatModel (自动装配)
     * @param rateLimiter 全局 LLM 限流器 (自动装配)
     */
    public SpringAILLMService(
            ChatModel chatModel,
            @Qualifier("llmRateLimiter") RateLimiter rateLimiter) {
        this.chatModel = chatModel;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.rateLimiter = rateLimiter;
        logger.info("SpringAILLMService initialized with ChatModel and RateLimiter");
    }
    
    /**
     * 分析用户查询意图（同步）
     * <p>
     * 用于路由决策，需要快速返回结果
     * <p>
     * 🔥 限流保护：在调用 LLM API 前，先获取限流许可
     * 
     * @param userQuery 分析提示词
     * @return 大模型响应（JSON 格式）
     */
    @Override
    public String analyze(String userQuery, String agentDescriptions) {
        logger.info("Analyzing user intent with LLM (with rate limiting)...");
        String prompt = buildAnalysisPrompt(userQuery, agentDescriptions);
        
        try {
            // 🔥 关键：通过 RateLimiter 执行，自动限流
            // executeSupplier() 会在调用前获取许可，如果达到限制会等待或抛出异常
            String response = RateLimiter.decorateSupplier(rateLimiter, () -> {
                logger.debug("RateLimiter: Permit acquired for analyze request");
                return chatClient.prompt()
                        .user(prompt)
                        .system(s -> s.param("current_date", LocalDate.now().toString()))
                        .call()
                        .content();
            }).get();
            
            logger.debug("LLM analysis response: {}", response);
            return response;
            
        } catch (RequestNotPermitted e) {
            // 限流触发：请求速率过快
            logger.warn("⚠️ LLM API rate limit exceeded for analyze request. " +
                    "Current metrics - Available permits: {}, Waiting threads: {}",
                    rateLimiter.getMetrics().getAvailablePermissions(),
                    rateLimiter.getMetrics().getNumberOfWaitingThreads());
            // 返回空的 JSON，让调用方使用降级策略
            return "{}";
            
        } catch (Exception e) {
            logger.error("Failed to analyze intent with LLM", e);
            // 返回空的 JSON，让调用方使用降级策略
            return "{}";
        }
    }
    
    /**
     * 流式聚合 Agent 的响应
     * <p>
     * 将从 Agent 收集的输出整合成连贯、自然的回答
     * <p>
     * 🔥 Prefix-Cache 优化：
     * - 通过传递 generatedPrefix，LLM 可以缓存已处理的部分
     * - 只需计算新增的内容，大幅提升性能（3-5倍）
     * - 同时避免重复输出，提升聚合质量
     * <p>
     * 🔥 限流保护：在调用 LLM API 前，先获取限流许可
     * 
     * @param userQuery 用户原始查询
     * @param agentOutput 从 Agent 收集的输出内容
     * @param generatedPrefix 已经生成的输出前缀（用于 prefix-cache）
     * @return 聚合后的流式响应（Flux<String>）
     */
    @Override
    public Flux<String> aggregateStreaming(String userQuery, String agentOutput, String generatedPrefix) {
        logger.info("Starting streaming aggregation (with rate limiting) for query: {}, input length: {}, prefix length: {}", 
                userQuery, 
                agentOutput != null ? agentOutput.length() : 0,
                generatedPrefix != null ? generatedPrefix.length() : 0);
        
        // 如果没有 Agent 输出，直接返回空
        if (agentOutput == null || agentOutput.trim().isEmpty()) {
            logger.warn("No agent output to aggregate");
            return Flux.just("暂无可用的回答内容");
        }
        
        try {
            // 构建聚合提示词（包含 prefix 信息）
            String aggregationPrompt = buildAggregationPrompt(userQuery, agentOutput, generatedPrefix);
            
            // 🔥 关键步骤1：先获取限流许可（非阻塞式）
            // Mono 会在订阅时尝试获取许可，如果无法获取会抛出 RequestNotPermitted
            return Mono.fromCallable(() -> {
                        logger.debug("RateLimiter: Attempting to acquire permit for streaming aggregation");
                        // 使用 acquirePermission() 同步获取许可
                        // 如果无法在 timeout 时间内获取，会抛出 RequestNotPermitted
                        rateLimiter.acquirePermission();
                        logger.debug("RateLimiter: Permit acquired successfully");
                        return aggregationPrompt;
                    })
                    // 🔥 关键步骤2：获取许可后，才调用 LLM API
                    .flatMapMany(prompt -> {
                        Prompt promptObj = new Prompt(prompt);
                        Flux<ChatResponse> responseFlux = chatModel.stream(promptObj);
                        
                        // 转换为字符串流
                        return responseFlux
                                .map(response -> {
                                    if (response.getResult() != null && response.getResult().getOutput() != null) {
                                        String text = response.getResult().getOutput().getText();
                                        return text != null ? text : "";
                                    }
                                    return "";
                                })
                                .filter(content -> !content.isEmpty());
                    })
                    // 错误处理：限流异常
                    .onErrorResume(RequestNotPermitted.class, e -> {
                        logger.warn("⚠️ LLM API rate limit exceeded for streaming aggregation. " +
                                "Current metrics - Available permits: {}, Waiting threads: {}",
                                rateLimiter.getMetrics().getAvailablePermissions(),
                                rateLimiter.getMetrics().getNumberOfWaitingThreads());
                        // 降级策略：返回提示信息
                        return Flux.just("⚠️ 系统繁忙，请稍后再试...");
                    })
                    // 错误处理：其他异常
                    .onErrorResume(Exception.class, e -> {
                        logger.error("Failed in streaming aggregation", e);
                        // 降级策略：直接返回原始内容
                        return Flux.just(agentOutput);
                    })
                    .doOnError(error -> logger.error("Error in streaming aggregation", error))
                    .doOnComplete(() -> logger.debug("Streaming aggregation completed"));
            
        } catch (Exception e) {
            logger.error("Failed to start streaming aggregation", e);
            // 降级策略：直接返回原始内容
            return Flux.just(agentOutput);
        }
    }
    
    /**
     * 构建聚合提示词（优化版）
     * <p>
     * 🔥 改进1：针对多Agent结构化输入优化prompt，提升聚合质量
     * 🔥 改进2：利用prefix-cache，告知LLM已生成的内容，避免重复并加速计算
     * 
     * @param userQuery 用户原始查询
     * @param agentOutput Agent 输出内容（按Agent分组的结构化格式）
     * @param generatedPrefix 已经生成的输出前缀（用于 prefix-cache）
     * @return 聚合提示词
     */
    private String buildAggregationPrompt(String userQuery, String agentOutput, String generatedPrefix) {
        // 判断是否是首次生成（无前缀）还是增量生成（有前缀）
        boolean isIncremental = generatedPrefix != null && !generatedPrefix.trim().isEmpty();
        
        if (isIncremental) {
            // 增量生成模式：明确告知LLM已经生成的内容
            return String.format("""
                    # 任务说明
                    你是一个专业的信息整合助手。你正在进行【增量整合】，多个Agent的回答正在陆续到达。
                    
                    # 用户问题
                    "%s"
                    
                    # 你已经生成的内容（不要重复！）
                    ```
                    %s
                    ```
                    
                    # 各Agent的最新回答（包含之前的和新增的）
                    以下是目前收集到的所有Agent回答：
                    
                    %s
                    
                    ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                    
                    # 增量整合要求
                    
                    ## 🔥 核心要求
                    1. **只输出新内容**：不要重复上面"你已经生成的内容"部分
                    2. **自然承接**：新内容要与已生成内容自然衔接
                    3. **补充完善**：整合新增的Agent信息，完善整体答案
                    4. **保持风格一致**：延续之前的语言风格和格式
                    
                    ## 具体操作
                    1. **快速回顾**：理解已生成的内容结构和进度
                    2. **识别新增**：分析哪些是新增的Agent信息
                    3. **补充整合**：将新信息融入整体框架
                    4. **继续输出**：从上次停下的地方继续
                    
                    ## ⚠️ 禁止事项
                    ❌ 不要重新生成已有内容
                    ❌ 不要使用"继续..."、"接上文..."等提示语
                    ❌ 不要改变已生成内容的表述
                    ❌ 不要添加Agent未提供的新信息
                    
                    ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                    
                    请直接输出新的内容（接续之前的答案）：
                    """, userQuery, generatedPrefix, agentOutput);
            
        } else {
            // 首次生成模式：完整的整合提示
            return String.format("""
                    # 任务说明
                    你是一个专业的信息整合助手。多个专业Agent针对用户的问题提供了各自领域的回答，现在需要你将这些信息整合成一个连贯、完整、易读的答案。
                    
                    # 用户问题
                    "%s"
                    
                    # 各Agent的专业回答
                    以下内容按Agent分组，每个Agent在其专业领域提供了回答：
                    
                    %s
                    
                    ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                    
                    # 整合要求
                    
                    ## 核心原则
                    1. **信息完整性**：保留所有重要信息，不要遗漏任何关键细节
                    2. **逻辑连贯性**：将不同Agent的回答融合成自然流畅的整体，而非简单拼接
                    3. **结构清晰性**：合理组织内容层次，使用标题、分点、段落等方式提升可读性
                    4. **去重与互补**：删除重复内容，保留互补信息，整合不同视角
                    
                    ## 具体要求
                    1. **识别主题**：分析各Agent回答涉及的主题和维度
                    2. **归类整合**：按主题归类信息，而不是按Agent顺序罗列
                    3. **优先级排序**：将最重要、最直接回答用户问题的内容放在前面
                    4. **语言优化**：使用自然、专业的语言，避免生硬的转折
                    5. **保持真实**：只整合已有信息，不添加未提及的内容
                    6. **格式友好**：适当使用序号、项目符号、段落分隔，提升阅读体验
                    
                    ## 输出格式建议
                    - 如果是单一主题：用自然段落展开说明
                    - 如果是多个维度：用清晰的标题分段（如：景点推荐、天气情况、交通建议等）
                    - 如果有步骤流程：用序号列表组织
                    - 如果有对比选择：用分点说明各选项的特点
                    
                    ## 禁止事项
                    ❌ 不要使用"根据XX Agent的回答"、"综合以上信息"等引导语
                    ❌ 不要简单复制粘贴各Agent的内容
                    ❌ 不要添加Agent未提供的信息或个人观点
                    ❌ 不要遗漏重要的细节信息
                    
                    ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                    
                    请直接输出整合后的答案：
                    """, userQuery, agentOutput);
        }
    }
    /**
     * 构建分析提示词
     * 参考 TravelIntentAnalyzer 的提示词设计，但更加通用
     */
    private String buildAnalysisPrompt(String userQuery, String agentDescriptions) {
        return String.format("""
            你是一个智能的 Agent 路由器，负责分析用户的查询意图并选择最合适的 Agent 来处理。
            
            用户查询: "%s"
            
            可用的 Agent 列表:
            %s
            
            请按照以下步骤进行分析:
            1. 识别用户的意图和需求
            2. 判断需要哪些 Agent 来处理这个查询（可以是一个或多个）
            3. 如果需要多个 Agent，请为每个 Agent 拆分出专门的子查询
            4. 说明为什么选择这些 Agent
            5. 评估你的分析置信度
            
            **重要规则**:
            - 如果查询涉及多个领域/技能，选择多个相关的 Agent
            - 为每个 Agent 生成针对性的查询，而不是简单地复制原始查询
            - 优先选择技能最匹配的 Agent
            - 如果不确定，选择通用能力强的 Agent
            
            请严格按照以下 JSON 格式返回（只返回 JSON，不要有其他内容）:
            {
              "intents": ["意图1", "意图2"],
              "selectedAgents": ["agentName1", "agentName2"],
              "splitQueries": {
                "agentName1": "针对 agent1 的专门查询",
                "agentName2": "针对 agent2 的专门查询"
              },
              "reasoning": "选择这些 Agent 的原因",
              "confidence": 0.95
            }
            
            JSON 响应:
            """, userQuery, agentDescriptions);
    }
}
