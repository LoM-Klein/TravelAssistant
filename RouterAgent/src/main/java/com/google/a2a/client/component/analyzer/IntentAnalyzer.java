package com.google.a2a.client.component.analyzer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.a2a.client.manager.AgentManager;
import com.google.a2a.client.model.IntentAnalysisResult;
import com.google.a2a.client.service.LLMService;
import com.travelassistant.common.model.AgentCard;
import com.travelassistant.common.model.AgentSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 意图分析器
 * <p>
 * 使用大模型分析用户意图，并选择合适的 Agent
 * 参考 RecommendAssistantAgent 的 TravelIntentAnalyzer 实现
 */
public class IntentAnalyzer {
    
    private static final Logger logger = LoggerFactory.getLogger(IntentAnalyzer.class);
    private final AgentManager agentManager;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    
    public IntentAnalyzer(AgentManager agentManager, LLMService llmService) {
        this.agentManager = agentManager;
        this.llmService = llmService;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 分析用户意图
     */
    public IntentAnalysisResult analyze(String userQuery) {
        logger.info("Analyzing user intent for query: {}", userQuery);
        
        // 1. 获取所有可用的 Agent 信息
        List<AgentManager.AgentInfo> availableAgents = agentManager.getAllAgents();
        
        if (availableAgents.isEmpty()) {
            logger.warn("No agents available for routing");
            return createEmptyResult(userQuery);
        }
        
        // 2. 动态构建Agent描述列表（从AgentCard获取详细信息）
        String agentDescriptions = buildAgentDescriptions(availableAgents);
        
        // 3. 使用大模型分析意图
        String analysisPrompt = buildAnalysisPrompt(userQuery, agentDescriptions);
        String llmResponse = llmService.analyze(analysisPrompt);
        
        // 4. 解析大模型响应
        return parseLLMResponse(llmResponse, userQuery, availableAgents);
    }
    
    /**
     * 构建 Agent 描述列表（动态从 AgentCard 获取详细信息）
     * 参考 TravelIntentAnalyzer 的实现，但更加通用化
     */
    private String buildAgentDescriptions(List<AgentManager.AgentInfo> agents) {
        StringBuilder descriptions = new StringBuilder();
        
        int index = 1;
        for (AgentManager.AgentInfo agent : agents) {
            // 过滤不可用的 Agent
            if (!agent.healthy() || agent.config() == null || !agent.config().enabled()) {
                continue;
            }
            
            AgentCard card = agent.card();
            
            descriptions.append(String.format("%d. **%s**\n", index++, agent.name()));
            
            // 描述信息（优先使用 AgentCard 中的描述）
            String description = card != null && card.description() != null 
                    ? card.description() 
                    : agent.config().description();
            descriptions.append(String.format("   - 描述: %s\n", description));
            
            // Skills 信息（优先使用 AgentCard 中的详细技能信息）
            if (card != null && card.skills() != null && !card.skills().isEmpty()) {
                descriptions.append("   - 技能:\n");
                for (AgentSkill skill : card.skills()) {
                    descriptions.append(String.format("     * %s: %s\n", 
                            skill.name(), 
                            skill.description() != null ? skill.description() : ""));
                    // 如果有示例，也加上
                    if (skill.examples() != null && !skill.examples().isEmpty()) {
                        descriptions.append(String.format("       示例: %s\n", 
                                String.join(", ", skill.examples())));
                    }
                }
            } else if (agent.config().skills() != null && !agent.config().skills().isEmpty()) {
                // 降级到配置中的简单技能列表
                descriptions.append(String.format("   - 技能关键词: %s\n", 
                        String.join(", ", agent.config().skills())));
            }
            
            // Provider 信息
            if (card != null && card.provider() != null) {
                descriptions.append(String.format("   - 提供方: %s\n", 
                        card.provider().organization()));
            }
            
            descriptions.append("\n");
        }
        
        return descriptions.toString();
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
    
    /**
     * 解析大模型响应
     * 参考 TravelIntentAnalyzer 的 parseCategoriesFromResponse 实现
     */
    private IntentAnalysisResult parseLLMResponse(
            String llmResponse, 
            String originalQuery,
            List<AgentManager.AgentInfo> availableAgents) {
        
        try {
            // 清理响应（去除可能的 markdown 代码块标记）
            String cleanedResponse = cleanJsonResponse(llmResponse);
            
            // 解析 JSON
            JsonNode jsonNode = objectMapper.readTree(cleanedResponse);
            
            // 提取字段
            List<String> intents = extractStringList(jsonNode, "intents");
            List<String> selectedAgents = extractStringList(jsonNode, "selectedAgents");
            Map<String, String> splitQueries = extractSplitQueries(jsonNode);
            String reasoning = jsonNode.has("reasoning") 
                    ? jsonNode.get("reasoning").asText() 
                    : "AI analysis";
            double confidence = jsonNode.has("confidence") 
                    ? jsonNode.get("confidence").asDouble() 
                    : 0.8;
            
            // 验证选择的 Agent 是否存在
            selectedAgents = validateSelectedAgents(selectedAgents, availableAgents);
            
            if (selectedAgents.isEmpty()) {
                logger.warn("No valid agents selected by LLM, using fallback");
                return fallbackAnalysis(originalQuery, availableAgents);
            }
            
            logger.info("Successfully parsed LLM response: {} intents, {} agents selected",
                    intents.size(), selectedAgents.size());
            
            return new IntentAnalysisResult(
                intents,
                selectedAgents,
                splitQueries,
                reasoning,
                confidence
            );
            
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse LLM JSON response: {}", llmResponse, e);
            return fallbackAnalysis(originalQuery, availableAgents);
        } catch (Exception e) {
            logger.error("Unexpected error parsing LLM response", e);
            return fallbackAnalysis(originalQuery, availableAgents);
        }
    }
    
    /**
     * 清理 JSON 响应（移除 markdown 标记等）
     */
    private String cleanJsonResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return "{}";
        }
        
        // 移除可能的 markdown 代码块标记
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        
        return cleaned.trim();
    }
    
    /**
     * 从 JSON 节点提取字符串列表
     */
    private List<String> extractStringList(JsonNode node, String fieldName) {
        List<String> result = new ArrayList<>();
        if (node.has(fieldName) && node.get(fieldName).isArray()) {
            for (JsonNode item : node.get(fieldName)) {
                result.add(item.asText());
            }
        }
        return result;
    }
    
    /**
     * 从 JSON 节点提取 splitQueries
     */
    private Map<String, String> extractSplitQueries(JsonNode node) {
        Map<String, String> result = new HashMap<>();
        if (node.has("splitQueries") && node.get("splitQueries").isObject()) {
            JsonNode queries = node.get("splitQueries");
            queries.fields().forEachRemaining(entry -> {
                result.put(entry.getKey(), entry.getValue().asText());
            });
        }
        return result;
    }
    
    /**
     * 验证选择的 Agent 是否都存在
     */
    private List<String> validateSelectedAgents(
            List<String> selectedAgents, 
            List<AgentManager.AgentInfo> availableAgents) {
        
        Set<String> availableNames = availableAgents.stream()
                .filter(a -> a.healthy() && a.config() != null && a.config().enabled())
                .map(AgentManager.AgentInfo::name)
                .collect(Collectors.toSet());
        
        return selectedAgents.stream()
                .filter(name -> {
                    if (availableNames.contains(name)) {
                        return true;
                    } else {
                        logger.warn("Agent '{}' selected by LLM but not available", name);
                        return false;
                    }
                })
                .collect(Collectors.toList());
    }
    
    /**
     * 创建空结果（当没有可用 Agent 时）
     */
    private IntentAnalysisResult createEmptyResult(String query) {
        return new IntentAnalysisResult(
            List.of(),
            List.of(),
            Map.of(),
            "No agents available",
            0.0
        );
    }
    
    /**
     * 降级策略：基于关键词匹配和智能查询拆分
     */
    private IntentAnalysisResult fallbackAnalysis(
            String query,
            List<AgentManager.AgentInfo> availableAgents) {
        
        logger.info("Using fallback analysis based on keyword matching");
        
        List<String> selectedAgents = new ArrayList<>();
        Map<String, String> splitQueries = new HashMap<>();
        List<String> intents = new ArrayList<>();
        
        String lowerQuery = query.toLowerCase();
        
        // 关键词匹配逻辑
        for (AgentManager.AgentInfo agent : availableAgents) {
            if (!agent.healthy() || agent.config() == null || !agent.config().enabled()) {
                continue;
            }
            
            List<String> skills = agent.config().skills();
            boolean matches = false;
            List<String> matchedSkills = new ArrayList<>();
            
            for (String skill : skills) {
                if (lowerQuery.contains(skill.toLowerCase())) {
                    matches = true;
                    matchedSkills.add(skill);
                    if (!intents.contains(skill)) {
                        intents.add(skill);
                    }
                }
            }
            
            if (matches) {
                selectedAgents.add(agent.name());
                // 生成针对该 Agent 的专用查询
                String specializedQuery = generateSpecializedQuery(query, agent, matchedSkills);
                splitQueries.put(agent.name(), specializedQuery);
            }
        }
        
        // 如果没有匹配到任何 Agent，选择优先级最高的
        if (selectedAgents.isEmpty() && !availableAgents.isEmpty()) {
            AgentManager.AgentInfo defaultAgent = availableAgents.stream()
                    .filter(a -> a.healthy() && a.config() != null && a.config().enabled())
                    .max(Comparator.comparingInt(a -> a.config().priority()))
                    .orElse(null);
            
            if (defaultAgent != null) {
                selectedAgents.add(defaultAgent.name());
                splitQueries.put(defaultAgent.name(), query);
                intents.add("general");
            }
        }
        
        return new IntentAnalysisResult(
            intents,
            selectedAgents,
            splitQueries,
            "Fallback analysis based on keyword matching",
            0.6
        );
    }
    
    /**
     * 生成针对特定 Agent 的专业查询
     * 参考 TravelIntentAnalyzer 的 generateSpecializedQuery 逻辑
     */
    private String generateSpecializedQuery(String originalQuery, AgentManager.AgentInfo agent, List<String> matchedSkills) {
        AgentCard card = agent.card();
        
        // 优先使用 AgentCard 中的技能信息
        if (card != null && card.skills() != null && !card.skills().isEmpty()) {
            // 基于匹配的技能生成专业查询
            if (matchedSkills.isEmpty() && !card.skills().isEmpty()) {
                // 使用第一个技能类型
                AgentSkill firstSkill = card.skills().get(0);
                String skillName = firstSkill.name();
                
                // 根据技能类型构造查询
                return constructQueryBySkillType(originalQuery, skillName);
            }
            
            // 使用第一个匹配的技能
            String firstMatchedSkill = matchedSkills.get(0);
            return constructQueryBySkillType(originalQuery, firstMatchedSkill);
        }
        
        // 降级到简单的查询构造
        if (!matchedSkills.isEmpty()) {
            String skill = matchedSkills.get(0);
            return constructQueryBySkillType(originalQuery, skill);
        }
        
        // 最终降级到原始查询
        return originalQuery;
    }
    
    /**
     * 根据技能类型构造查询
     */
    private String constructQueryBySkillType(String originalQuery, String skillType) {
        String lowerSkill = skillType.toLowerCase();
        
        // 针对常见技能类型构造专门查询
        if (lowerSkill.contains("translat") || lowerSkill.contains("翻译")) {
            return "翻译以下内容：" + originalQuery;
        } else if (lowerSkill.contains("travel") || lowerSkill.contains("travel") || lowerSkill.contains("旅游")) {
            return "关于旅游咨询：" + originalQuery;
        } else if (lowerSkill.contains("weather") || lowerSkill.contains("天气")) {
            return "查询天气信息：" + originalQuery;
        } else if (lowerSkill.contains("hotel") || lowerSkill.contains("酒店")) {
            return "关于酒店预订：" + originalQuery;
        } else if (lowerSkill.contains("food") || lowerSkill.contains("美食")) {
            return "关于美食推荐：" + originalQuery;
        } else {
            // 默认：在查询前加上技能上下文
            return "请使用 " + skillType + " 能力处理：" + originalQuery;
        }
    }
}

