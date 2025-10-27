package com.ai.recommend.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * LLM 应该返回的结构化数据模型：包含多个语义切块。
 */
public record ClassifiedChunks(
        @JsonPropertyDescription("从原始文本中提取出的、语义连贯且带分类标签的切块列表")
        List<Chunk> chunks
) {
    /**
     * 单个语义切块
     */
    public record Chunk(
            @JsonPropertyDescription("该切块的原始内容文本")
            String content,

            @JsonPropertyDescription("该切块涉及的主题列表，必须从 TRANSPORT, ACCOMMODATION, FOOD, ATTRACTION, GUIDE 中选择")
            List<String> categories
    ) {}
}
