package com.ai.recommend.model;

/**
 * 旅行类别枚举
 * 定义旅行攻略的主要分类
 *
 * @author Travel Agent
 * @since 1.0.0
 */
public enum TravelCategory {

    /**
     * 交通类 - 出行方式、路线规划、时间安排
     */
    TRANSPORT("交通", "出行方式、路线规划、时间安排"),

    /**
     * 住宿类 - 酒店推荐、民宿选择、价格分析
     */
    ACCOMMODATION("住宿", "酒店推荐、民宿选择、价格分析"),

    /**
     * 美食类 - 特色餐厅、当地美食、价格区间
     */
    FOOD("美食", "特色餐厅、当地美食、价格区间"),

    /**
     * 景点类 - 必游景点、小众景点、游览时间
     */
    ATTRACTION("景点", "必游景点、小众景点、游览时间"),

    /**
     * 攻略类 - 完整方案、预算规划、注意事项
     */
    GUIDE("攻略", "完整方案、预算规划、注意事项");

    private final String name;
    private final String description;

    TravelCategory(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据名称获取类别
     * @param name 类别名称
     * @return 对应的类别，如果未找到返回null
     */
    public static TravelCategory fromName(String name) {
        for (TravelCategory category : values()) {
            if (category.getName().equals(name)) {
                return category;
            }
        }
        return null;
    }

    /**
     * 检查是否包含指定关键词
     * @param keyword 关键词
     * @return 是否包含
     */
    public boolean containsKeyword(String keyword) {
        return name.contains(keyword) || description.contains(keyword);
    }
}
