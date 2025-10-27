package com.ai.recommend.model;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.HashMap;

/**
 * 专业查询模型
 * 用于拆分复合查询为多个专业查询
 *
 * @author Travel Agent
 * @since 1.0.0
 */
@Getter
@Setter
public class SpecializedQuery {

    private String id;
    private String originalQuery;
    private TravelCategory category;
    private String specializedQuery;
    private Map<String, Object> parameters;
    private LocalDateTime createdAt;
    private int priority;

    // 默认构造函数
    public SpecializedQuery() {
        this.parameters = new HashMap<>();
        this.createdAt = LocalDateTime.now();
        this.priority = 1;
    }

    // 构造函数
    public SpecializedQuery(String originalQuery, TravelCategory category, String specializedQuery) {
        this();
        this.originalQuery = originalQuery;
        this.category = category;
        this.specializedQuery = specializedQuery;
        this.id = generateId();
    }


    /**
     * 添加参数
     * @param key 键
     * @param value 值
     */
    public void addParameter(String key, Object value) {
        if (this.parameters == null) {
            this.parameters = new HashMap<>();
        }
        this.parameters.put(key, value);
    }


    /**
     * 生成唯一ID
     * @return 唯一ID
     */
    private String generateId() {
        return category.name() + "_" + System.currentTimeMillis() + "_" + hashCode();
    }

    @Override
    public String toString() {
        return "SpecializedQuery{" +
                "id='" + id + '\'' +
                ", category=" + category +
                ", specializedQuery='" + specializedQuery + '\'' +
                ", priority=" + priority +
                '}';
    }
}
