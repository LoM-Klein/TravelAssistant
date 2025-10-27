package com.ai.recommend.componet.analyser;

import com.ai.recommend.model.SpecializedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.ai.recommend.model.TravelCategory;


/**
 * 旅行意图分析器
 * 负责分析用户查询意图，识别涉及的旅行类别，并拆分复合查询
 *
 * @author Travel Agent
 * @since 1.0.0
 */
@Component
public class TravelIntentAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(TravelIntentAnalyzer.class);

    private final ChatClient chatClient;

    // 关键词映射

    // 地点关键词
    private static final Pattern LOCATION_PATTERN = Pattern.compile("(北京|上海|广州|深圳|杭州|南京|苏州|成都|重庆|西安|武汉|长沙|青岛|大连|厦门|三亚|丽江|桂林|黄山|张家界|九寨沟|西藏|新疆|内蒙古|云南|贵州|四川|湖南|湖北|江苏|浙江|广东|福建|山东|辽宁|吉林|黑龙江|河北|河南|山西|陕西|甘肃|青海|宁夏|台湾|香港|澳门)");

    public TravelIntentAnalyzer(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 分析用户查询意图
     * @param userMessage 用户查询消息
     * @return 涉及的旅行类别列表
     */
    public List<TravelCategory> analyzeIntent(String userMessage) {
        logger.info("分析用户查询意图: {}", userMessage);

        // 2. 基于AI模型的智能识别
        List<TravelCategory> categories = new ArrayList<>(analyzeByAI(userMessage));

        // 3. 去重并排序
        categories = categories.stream()
                .distinct()
                .sorted(Comparator.comparing(TravelCategory::ordinal))
                .collect(Collectors.toList());

        logger.info("识别到的旅行类别: {}", categories);
        return categories;
    }

    /**
     * 拆分复合查询为多个专业查询
     * @param userMessage 原始查询
     * @param categories 识别的类别
     * @return 拆分后的专业查询列表
     */
    public List<SpecializedQuery> splitQuery(String userMessage, List<TravelCategory> categories) {
        logger.info("拆分查询: {} -> {}", userMessage, categories);

        List<SpecializedQuery> queries = new ArrayList<>();

        for (TravelCategory category : categories) {
            String specializedQuery = generateSpecializedQuery(userMessage, category);
            SpecializedQuery query = new SpecializedQuery(userMessage, category, specializedQuery);

            // 设置优先级
            query.setPriority(getCategoryPriority(category));

            // 添加参数
            addQueryParameters(query, userMessage, category);

            queries.add(query);
        }

        // 按优先级排序
        queries.sort(Comparator.comparing(SpecializedQuery::getPriority));

        logger.info("拆分后的专业查询: {}", queries);
        return queries;
    }



    /**
     * 基于AI模型分析意图
     * @param userMessage 用户消息
     * @return 识别的类别
     */
    private List<TravelCategory> analyzeByAI(String userMessage) {
        try {
            String prompt = String.format("""
                请分析以下用户查询涉及哪些旅行类别，从以下选项中选择：
                1. TRANSPORT - 交通相关
                2. ACCOMMODATION - 住宿相关
                3. FOOD - 美食相关
                4. ATTRACTION - 景点相关
                5. GUIDE - 攻略相关
                
                用户查询：%s
                
                请只返回类别代码，多个类别用逗号分隔，如：TRANSPORT,FOOD
                """, userMessage);

            String response = chatClient.prompt()
                    .user(prompt)
                    .system(s -> s.param("current_date", LocalDate.now().toString()))
                    .call()
                    .content();

            return parseCategoriesFromResponse(response);

        } catch (Exception e) {
            logger.warn("AI意图识别失败，使用关键词识别: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 解析AI响应中的类别
     * @param response AI响应
     * @return 类别列表
     */
    private List<TravelCategory> parseCategoriesFromResponse(String response) {
        List<TravelCategory> categories = new ArrayList<>();

        if (response != null && !response.trim().isEmpty()) {
            String[] parts = response.trim().split(",");
            for (String part : parts) {
                try {
                    TravelCategory category = TravelCategory.valueOf(part.trim());
                    categories.add(category);
                } catch (IllegalArgumentException e) {
                    logger.warn("无法解析类别: {}", part);
                }
            }
        }

        return categories;
    }

    /**
     * 生成专业查询
     * @param originalQuery 原始查询
     * @param category 类别
     * @return 专业查询
     */
    private String generateSpecializedQuery(String originalQuery, TravelCategory category) {
        return switch (category) {
            case TRANSPORT -> "关于" + extractLocation(originalQuery) + "的交通出行方式和路线规划";
            case ACCOMMODATION -> "关于" + extractLocation(originalQuery) + "的住宿推荐和酒店选择";
            case FOOD -> "关于" + extractLocation(originalQuery) + "的美食推荐和特色餐厅";
            case ATTRACTION -> "关于" + extractLocation(originalQuery) + "的景点推荐和游玩攻略";
            case GUIDE -> "关于" + extractLocation(originalQuery) + "的完整旅行攻略和行程安排";
        };
    }

    /**
     * 提取地点信息
     * @param query 查询
     * @return 地点
     */
    private String extractLocation(String query) {
        // 使用正则表达式提取地点
        java.util.regex.Matcher matcher = LOCATION_PATTERN.matcher(query);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "该地区";
    }

    /**
     * 获取类别优先级
     * @param category 类别
     * @return 优先级
     */
    private int getCategoryPriority(TravelCategory category) {
        return switch (category) {
            case GUIDE -> 1;      // 攻略优先级最高
            case TRANSPORT -> 2;  // 交通次之
            case ACCOMMODATION -> 3; // 住宿
            case ATTRACTION -> 4; // 景点
            case FOOD -> 5;       // 美食
        };
    }

    /**
     * 添加查询参数
     * @param query 查询对象
     * @param userMessage 用户消息
     * @param category 类别
     */
    private void addQueryParameters(SpecializedQuery query, String userMessage, TravelCategory category) {
        // 添加地点参数
        String location = extractLocation(userMessage);
        if (!location.equals("该地区")) {
            query.addParameter("location", location);
        }

        // 添加时间参数（如果存在）
        if (userMessage.contains("天") || userMessage.contains("日")) {
            query.addParameter("duration", extractDuration(userMessage));
        }

        // 添加预算参数（如果存在）
        if (userMessage.contains("便宜") || userMessage.contains("经济") || userMessage.contains("豪华")) {
            query.addParameter("budget", extractBudget(userMessage));
        }

        // 添加季节参数（如果存在）
        if (userMessage.contains("春") || userMessage.contains("夏") || userMessage.contains("秋") || userMessage.contains("冬")) {
            query.addParameter("season", extractSeason(userMessage));
        }
    }

    /**
     * 提取持续时间
     * @param query 查询
     * @return 持续时间
     */
    private String extractDuration(String query) {
        // 简单的持续时间提取逻辑
        if (query.contains("1天") || query.contains("一日")) return "1天";
        if (query.contains("2天") || query.contains("两日")) return "2天";
        if (query.contains("3天") || query.contains("三日")) return "3天";
        if (query.contains("一周") || query.contains("7天")) return "7天";
        return "多天";
    }

    /**
     * 提取预算信息
     * @param query 查询
     * @return 预算类型
     */
    private String extractBudget(String query) {
        if (query.contains("便宜") || query.contains("经济")) return "经济型";
        if (query.contains("豪华") || query.contains("高端")) return "豪华型";
        return "中等";
    }

    /**
     * 提取季节信息
     * @param query 查询
     * @return 季节
     */
    private String extractSeason(String query) {
        if (query.contains("春")) return "春季";
        if (query.contains("夏")) return "夏季";
        if (query.contains("秋")) return "秋季";
        if (query.contains("冬")) return "冬季";
        return "全年";
    }
}

