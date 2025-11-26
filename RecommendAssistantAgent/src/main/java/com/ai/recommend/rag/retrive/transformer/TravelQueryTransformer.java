package com.ai.recommend.rag.retrive.transformer;

import com.ai.recommend.model.SpecializedQuery;
import com.ai.recommend.model.TravelCategory;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TravelQueryTransformer implements QueryExpander {

    private static final Logger logger = LoggerFactory.getLogger(TravelQueryTransformer.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatModel chatModel;

    // 构造函数注入：Spring 会自动查找并注入 ChatModel 类型的 Bean
    public TravelQueryTransformer (ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 统一的意图识别和查询拆分方法
     * 使用单一LLM调用同时完成意图识别和查询拆分
     * @param userquery 用户查询消息
     * @return 拆分后的专业查询列表
     */
    @NotNull
    @Override
    public List<Query> expand(Query userquery) {
        String userMessage = userquery.text();
        List<Query> multiQuery = new ArrayList<>();

        List<SpecializedQuery> queryList =  callLLMForAnalysis(userMessage);

        for(SpecializedQuery query : queryList){
            Map<String,Object> filter  =new HashMap<>();
            Filter.Expression categoryFilter = new FilterExpressionBuilder()
                    .eq("category", query.getCategory())
                    .build();
            filter.put("vector_store_filter_expression",categoryFilter);
            Query search = Query.builder().text(query.getSpecializedQuery()).context(filter).build();
            multiQuery.add(search);
        }
        return multiQuery;
    }

    /**
     * 调用LLM进行统一的意图分析和查询拆分
     * @param userMessage 用户查询
     * @return LLM的JSON响应
     */
    private List<SpecializedQuery> callLLMForAnalysis(String userMessage) {
        String analysisSchema = """
            请分析以下用户的旅行查询，完成两个任务：
            1. 识别查询涉及的旅行类别
            2. 将查询拆分为针对每个类别的专业查询
            
            用户查询：%s
            
            旅行类别说明：
            - TRANSPORT: 交通出行（飞机、火车、地铁、公交、租车等）
            - ACCOMMODATION: 住宿相关（酒店、民宿、青旅等）
            - FOOD: 美食相关（餐厅、特色小吃、美食街等）
            - ATTRACTION: 景点相关（名胜古迹、自然风光、游乐场等）
            - GUIDE: 旅行攻略（行程规划、完整攻略、注意事项等）
            
            请以JSON格式返回结果，格式如下：
            {
              "queries": [
                {
                  "category": "类别代码",
                  "specializedQuery": "针对该类别的专业查询",
                }
              ]
            }
            
            注意事项：
            1. 根据用户查询的实际内容识别相关类别，可能包含一个或多个类别
            2. 为每个类别生成针对性的专业查询，不要简单复制原查询
            """;


        logger.debug("发送LLM请求进行统一分析");

        ReactAgent agent = ReactAgent.builder()
                .name("text_analyzer")
                .model(chatModel)
                .outputSchema(analysisSchema.formatted(userMessage))
                .build();

        try {
            AssistantMessage message = agent.call(userMessage);
            String content = message.getText();

            JsonNode rootNode = objectMapper.readTree(content);
            JsonNode queriesNode = rootNode.get("queries");

            List<SpecializedQuery> result = new ArrayList<>();
            if (queriesNode != null && queriesNode.isArray()) {
                for (JsonNode queryNode : queriesNode) {
                    String categoryStr = queryNode.get("category").asText();
                    String specializedQuery = queryNode.get("specializedQuery").asText();

                    TravelCategory category = TravelCategory.valueOf(categoryStr);
                    result.add(new SpecializedQuery(userMessage, category, specializedQuery));
                }
            }

            return result;
        } catch (Exception e) {
            logger.error("解析LLM响应失败", e);
            throw new RuntimeException("解析LLM响应失败", e);
        }
    }
}
