package com.ai.recommend.config.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.ContentFormatter;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.util.Assert;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.Math;

public class TripleLimitBatchingStrategy implements BatchingStrategy {

    private final TokenCountEstimator tokenCountEstimator;
    private final int maxInputTokenCount; // 批次 Token 总数限制
    private final int maxDocumentCount;   // 批次文档数量限制
    private final int maxSingleDocumentTokenCount; // <--- 新增：单文档 Token 限制
    private final ContentFormatter contentFormatter;
    private final MetadataMode metadataMode;

    // 默认常量
    private static final int DEFAULT_MAX_BATCH_TOKENS = 8191;
    private static final double DEFAULT_TOKEN_COUNT_RESERVE_PERCENTAGE = 0.1;

    /**
     * 默认构造函数，使用默认的 Token 限制 (8191), 文档限制 (25), 单文档限制 (2048)
     */
    public TripleLimitBatchingStrategy() {
        this(EncodingType.CL100K_BASE, DEFAULT_MAX_BATCH_TOKENS, DEFAULT_TOKEN_COUNT_RESERVE_PERCENTAGE, 25, 2048);
    }

    /**
     * 完整的构造函数，包含所有限制参数
     */
    public TripleLimitBatchingStrategy(EncodingType encodingType, int maxBatchTokenCount,
                                       double reservePercentage, int maxDocumentCount,
                                       int maxSingleDocumentTokenCount) {
        this(encodingType, maxBatchTokenCount, reservePercentage, maxDocumentCount,
                maxSingleDocumentTokenCount, Document.DEFAULT_CONTENT_FORMATTER, MetadataMode.NONE);
    }

    /**
     * 最完整的构造函数
     */
    public TripleLimitBatchingStrategy(EncodingType encodingType, int maxBatchTokenCount,
                                       double reservePercentage, int maxDocumentCount,
                                       int maxSingleDocumentTokenCount,
                                       ContentFormatter contentFormatter, MetadataMode metadataMode) {

        // 参数校验
        Assert.isTrue(maxDocumentCount > 0, "MaxDocumentCount must be greater than 0");
        Assert.isTrue(maxSingleDocumentTokenCount > 0, "MaxSingleDocumentTokenCount must be greater than 0");
        Assert.isTrue(maxBatchTokenCount >= maxSingleDocumentTokenCount, "Batch Token Count must be >= Single Document Token Count"); // 批次限制不能小于单文档限制
        Assert.isTrue(reservePercentage >= 0.0F && reservePercentage < 1.0F, "ReservePercentage must be in range [0, 1)");

        this.tokenCountEstimator = new JTokkitTokenCountEstimator(encodingType);
        // 计算批次 Token 的安全阈值
        this.maxInputTokenCount = (int)Math.round((double)maxBatchTokenCount * (1.0F - reservePercentage));
        this.maxDocumentCount = maxDocumentCount;
        this.maxSingleDocumentTokenCount = maxSingleDocumentTokenCount; // 设置新的单文档限制
        this.contentFormatter = contentFormatter;
        this.metadataMode = metadataMode;
    }

    @Override
    public List<List<Document>> batch(List<Document> documents) {
        List<List<Document>> batches = new ArrayList<>();
        int currentTokenSize = 0;
        int currentDocumentCount = 0;
        List<Document> currentBatch = new ArrayList<>();
        Map<Document, Integer> documentTokens = new LinkedHashMap<>();

        // 1. 预计算所有文档的 Token 数量，并执行【单文档限制检查】
        for (Document document : documents) {
            int tokenCount = this.tokenCountEstimator.estimate(
                    document.getFormattedContent(this.contentFormatter, this.metadataMode)
            );

            // 检查：如果单个文档的 Token 超过了单文档的限制
            if (tokenCount > this.maxSingleDocumentTokenCount) {
                throw new IllegalArgumentException(String.format(
                        "Tokens in a single document (%d) exceeds the maximum allowed single document tokens (%d). Please chunk your documents.",
                        tokenCount, this.maxSingleDocumentTokenCount
                ));
            }

            // 检查：如果单个文档的 Token 超过了批次的限制（尽管通常不会发生，但以防万一）
            if (tokenCount > this.maxInputTokenCount) {
                throw new IllegalArgumentException(String.format(
                        "Tokens in a single document (%d) exceeds the maximum allowed batch tokens (%d). This should not happen if maxSingleDocumentTokenCount is correctly set.",
                        tokenCount, this.maxInputTokenCount
                ));
            }

            documentTokens.put(document, tokenCount);
        }

        // 2. 遍历并执行【双重批次限制】分批逻辑
        for (Document document : documentTokens.keySet()) {
            Integer tokenCount = documentTokens.get(document);

            // 检查是否达到任一限制，决定是否开启新批次
            // A. Token 数量超限 (批次总Token)
            // B. 文档数量达到上限 (批次总文档数)
            if (currentTokenSize + tokenCount > this.maxInputTokenCount ||
                    currentDocumentCount >= this.maxDocumentCount)
            {
                // 超限，结束当前批次，开启新批次
                batches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentTokenSize = 0;
                currentDocumentCount = 0;
            }

            // 将文档添加到当前批次
            currentBatch.add(document);
            currentTokenSize += tokenCount;
            currentDocumentCount++;
        }

        // 3. 添加最后一个未满的批次
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        return batches;
    }
}