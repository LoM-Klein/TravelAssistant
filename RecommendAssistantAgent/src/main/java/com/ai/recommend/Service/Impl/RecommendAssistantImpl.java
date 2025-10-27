package com.ai.recommend.Service.Impl;

import com.ai.recommend.Service.RecommendAssistant;
import com.ai.recommend.componet.analyser.TravelIntentAnalyzer;
import com.ai.recommend.model.SpecializedQuery;
import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import com.ai.recommend.model.TravelCategory;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;


@Service
public class RecommendAssistantImpl implements RecommendAssistant {

        private static final Logger logger = LoggerFactory.getLogger(RecommendAssistantImpl.class);
        private final RerankModel rerankModel;
        private final VectorStore vectorStore;
        private final ChatClient chatClient;
        private final TravelIntentAnalyzer intentAnalyzer;
        private final PromptTemplate summaryPromptTemplate;
        /**
         * 构造函数，注入RAG服务所需的依赖组件
         * @param vectorStore 向量存储，用于文档向量化存储和检索
         */
        public RecommendAssistantImpl(ChatClient.Builder modelBuilder, VectorStore vectorStore, TravelIntentAnalyzer travelIntentAnalyzer, ChatMemory chatMemory, RerankModel rerankModel,PromptTemplate systemPromptTemplate,
                                      PromptTemplate summaryPromptTemplate) {
            this.vectorStore = vectorStore;
            this.intentAnalyzer = travelIntentAnalyzer;
            this.summaryPromptTemplate = summaryPromptTemplate;
            this.chatClient = modelBuilder
                    .defaultSystem(systemPromptTemplate.getTemplate())
                    .defaultAdvisors(
                            PromptChatMemoryAdvisor.builder(chatMemory).build(), // 记忆能力
                            //new QuestionAnswerAdvisor(vectorStore), // RAG检索
                            new SimpleLoggerAdvisor() // 日志记录
                    )
                    .build();
            this.rerankModel = rerankModel;
        }
    /**
     * 智能查询处理
     * 分析用户意图，拆分查询，并行检索，使用ChatClient生成智能回答
     *
     * @param userMessage 用户查询
     * @return 处理结果
     */
    public Flux<ChatResponse>travelQuery(String chatID,String userMessage) {
        logger.info("开始智能查询处理: {}", userMessage);

        try {
            List<Document> context = new ArrayList<>();
            
            // 1. 意图识别
            List<TravelCategory> categories = intentAnalyzer.analyzeIntent(userMessage);
            
            if (!categories.isEmpty()) {
                // 2. 查询拆分
                List<SpecializedQuery> queries = intentAnalyzer.splitQuery(userMessage, categories);
                
                if (!queries.isEmpty()) {
                    // 3. 并行检索
                    Map<TravelCategory, List<Document>> searchResults = parallelSearch(queries);
                    
                    // 4. 构建上下文信息
                    context = rerankMultiCategoryResults(searchResults, userMessage, rerankModel, 0.1);
                } else {
                    logger.warn("查询拆分失败，将交由大模型处理: {}", userMessage);
                }
            } else {
                logger.warn("意图识别失败，将交由大模型处理: {}", userMessage);
            }

            // 5. 使用ChatClient生成智能回答（无论是否有上下文，都交给大模型处理）
            return generateIntelligentResponse(userMessage, context, chatID);

        } catch (Exception e) {
            logger.error("智能查询处理失败", e);
            // 即使发生异常，也尝试让大模型处理
            return generateIntelligentResponse(userMessage, new ArrayList<>(), chatID);
        }
    }

    /**
     * 并行检索多个类别的信息
     * @param queries 专业查询列表
     * @return 检索结果
     */
    private Map<TravelCategory, List<Document>> parallelSearch(List<SpecializedQuery> queries) {
        logger.info("开始并行检索，查询数量: {}", queries.size());

        Map<TravelCategory, CompletableFuture<List<Document>>> futures = new HashMap<>();

        // 创建并行检索任务
        for (SpecializedQuery query : queries) {
            CompletableFuture<List<Document>> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return performSearch(query);
                } catch (Exception e) {
                    logger.error("检索失败: {}", query, e);
                    return new ArrayList<>();
                }
            });
            futures.put(query.getCategory(), future);
        }

        // 等待所有任务完成
        Map<TravelCategory, List<Document>> results = new HashMap<>();
        for (Map.Entry<TravelCategory, CompletableFuture<List<Document>>> entry : futures.entrySet()) {
            try {
                results.put(entry.getKey(), entry.getValue().get());
            } catch (Exception e) {
                logger.error("等待检索结果失败: {}", entry.getKey(), e);
                results.put(entry.getKey(), new ArrayList<>());
            }
        }

        logger.info("并行检索完成，结果数量: {}", results.size());
        return results;
    }

    /**
     * 执行单个查询的检索
     * @param query 专业查询
     * @return 检索结果
     */
    private List<Document> performSearch(SpecializedQuery query) {
        logger.debug("执行检索: {}", query);

        SearchRequest searchRequest = SearchRequest.builder()
                .topK(3) // 每个类别返回前3个结果
                .similarityThresholdAll()
                .query(query.getSpecializedQuery())
                .build();

        try {
            return vectorStore.similaritySearch(searchRequest);
        } catch (Exception e) {
            logger.error("向量检索失败: {}", query, e);
            return new ArrayList<>();
        }
    }

    public List<Document> rerankMultiCategoryResults(
            Map<TravelCategory, List<Document>> searchResults,
            String query,
            RerankModel rerankModel,
            Double minScore
    ) {
        // 1. 合并文档
        List<Document> allDocuments = searchResults.values().stream()
                .flatMap(List::stream) // 将所有 List<Document> 流扁平化
                .collect(Collectors.toList());

        if (CollectionUtils.isEmpty(allDocuments)) {
            return Collections.emptyList();
        }

        // 2. 执行 Re-rank
        RerankRequest rerankRequest = new RerankRequest(query, allDocuments);
        RerankResponse response = rerankModel.call(rerankRequest);

        // 3. 过滤和排序 (与原 doRerank 逻辑相同)
        if (response == null || CollectionUtils.isEmpty(response.getResults())) {
            return allDocuments; // Rerank失败，返回原始文档（或空列表，取决于业务需求）
        }

        return response.getResults().stream()
                .filter(docWithScore -> docWithScore != null && docWithScore.getScore() >= minScore)
                .sorted(Comparator.comparingDouble(DocumentWithScore::getScore).reversed()) // 降序排列
                .map(DocumentWithScore::getOutput)
                .collect(Collectors.toList());
    }

    /**
     * 使用ChatClient生成智能回答
     * @param userMessage 用户查询
     * @param context 上下文信息（可能为空）
     * @return 智能回答
     */
    private Flux<ChatResponse> generateIntelligentResponse(String userMessage, List<Document> context, String chatID) {
        logger.info("使用ChatClient生成智能回答，上下文数量: {}", context.size());

        try {
            String documentContext;
            
            // 根据是否有上下文信息，构建不同的提示
            if (context.isEmpty()) {
                logger.info("未找到相关攻略信息，让大模型自主处理");
                documentContext = "抱歉，我在知识库中没有找到与您问题直接相关的攻略信息。" +
                        "但我可以基于我的旅行知识为您提供一些通用建议。";
            } else {
                // 格式化上下文
                documentContext = context.stream()
                        .map(Document::getText)
                        .collect(Collectors.joining(System.lineSeparator() + "---" + System.lineSeparator()));
            }

            // 渲染 PromptTemplate，得到完整的 Prompt 对象
            Prompt prompt = this.summaryPromptTemplate.create(Map.of(
                    "user_query", userMessage,
                    "question_answer_context", documentContext
            ));

            // 使用 chatClient 生成响应
            return chatClient.prompt(prompt)
                    .advisors(
                            // 设置advisor参数 (用于记忆)
                            a -> a.param(ChatMemory.DEFAULT_CONVERSATION_ID, chatID))
                    .stream()
                    .chatResponse();

        } catch (Exception e) {
            logger.error("生成智能回答失败", e);
            return Flux.error(e);
        }
    }

}





