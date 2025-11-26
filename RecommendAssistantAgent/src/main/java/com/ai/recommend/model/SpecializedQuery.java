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
    private LocalDateTime createdAt;

    // 默认构造函数
    public SpecializedQuery() {
        this.createdAt = LocalDateTime.now();
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
                ", specializedQuery='" + specializedQuery +"}";
    }
}
