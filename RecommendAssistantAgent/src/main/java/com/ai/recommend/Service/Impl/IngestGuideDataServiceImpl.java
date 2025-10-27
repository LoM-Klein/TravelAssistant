package com.ai.recommend.Service.Impl;

import com.ai.recommend.Service.IngestGuideDataService;
import com.ai.recommend.model.ClassifiedChunks;
import com.ai.recommend.componet.prosessor.EmbeddingDocuments;
import com.ai.recommend.componet.prosessor.MyTextReader;
import com.ai.recommend.componet.prosessor.SemanticChunker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据摄取服务实现
 * 负责将旅行攻略数据加载、处理并存储到向量数据库
 *
 * @author Travel Agent
 * @since 1.0.0
 */
@Service
@Slf4j
public class IngestGuideDataServiceImpl implements IngestGuideDataService {

    private final VectorStore vectorStore;
    private final SemanticChunker semanticChunker;
    private final MyTextReader myTextReader;
    
    // 批处理大小，可配置
    @Value("${app.ingest.batch-size:50}")
    private int batchSize;
    
    // 最大重试次数
    @Value("${app.ingest.max-retries:3}")
    private int maxRetries;

    IngestGuideDataServiceImpl(VectorStore vectorStore, SemanticChunker semanticChunker,MyTextReader myTextReader) {
        this.vectorStore = vectorStore;
        this.semanticChunker = semanticChunker;
        this.myTextReader = myTextReader;
    }

    @Override
    @Retryable(
        value = {Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    public void ingestGuideData() {
        Instant startTime = Instant.now();
        log.info("========================================");
        log.info("🚀 开始攻略数据摄取流程");
        log.info("========================================");
        
        IngestStatistics stats = new IngestStatistics();
        
        try {
            // 第1步: 提取 (Extract)
            log.info("【步骤 1/4】提取原始数据...");
            List<Document> rawDocuments = extractRawDocuments();
            stats.rawDocumentCount = rawDocuments.size();
            log.info("✓ 提取完成: 共 {} 个原始文档", rawDocuments.size());
            
            if (rawDocuments.isEmpty()) {
                log.warn("⚠️ 没有找到任何文档，摄取流程终止");
                return;
            }
            
            // 第2步: 语义切块和分类 (Transform - Chunking & Classification)
            log.info("【步骤 2/3】语义切块和分类（调用 LLM）...");
            ClassifiedChunks categorizedChunks = chunkAndClassify(rawDocuments);
            stats.chunkCount = categorizedChunks.chunks().size();
            log.info("✓ 切块和分类完成: 得到 {} 个语义切块", stats.chunkCount);
            
            // 第3步: 转换为 Document 并批量存储（VectorStore 会自动进行向量化）
            log.info("【步骤 3/3】转换文档并存储到向量数据库（批量处理，批大小: {}）...", batchSize);
            log.info("ℹ️ VectorStore 将自动对文档进行向量化处理");
            List<Document> documents = convertToDocuments(categorizedChunks);
            stats.embeddedDocumentCount = documents.size();
            int storedCount = batchStoreToVectorStore(documents);
            stats.storedDocumentCount = storedCount;
            log.info("✓ 存储完成: 成功存储 {} 个文档", storedCount);
            
            // 计算耗时
            Duration duration = Duration.between(startTime, Instant.now());
            stats.durationSeconds = duration.getSeconds();
            
            // 打印统计信息
            printStatistics(stats);
            
            log.info("========================================");
            log.info("✅ 数据摄取流程成功完成");
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("========================================");
            log.error("❌ 数据摄取流程失败");
            log.error("========================================");
            log.error("错误详情: {}", e.getMessage(), e);
            throw new RuntimeException("数据摄取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 提取原始文档
     */
    private List<Document> extractRawDocuments() {
        try {
            return myTextReader.loadText();
        } catch (Exception e) {
            log.error("提取原始文档失败", e);
            throw new RuntimeException("文档提取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 语义切块和分类
     */
    private ClassifiedChunks chunkAndClassify(List<Document> rawDocuments) {
        try {
            return semanticChunker.chunkAndClassify(rawDocuments);
        } catch (Exception e) {
            log.error("语义切块和分类失败", e);
            throw new RuntimeException("切块分类失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 ClassifiedChunks 转换为 Document 列表
     * VectorStore 会自动处理向量化
     */
    private List<Document> convertToDocuments(ClassifiedChunks categorizedChunks) {
        try {
            if (categorizedChunks == null || categorizedChunks.chunks().isEmpty()) {
                return List.of();
            }
            
            return categorizedChunks.chunks().stream().map(chunk -> {
                Map<String, Object> metadata = new java.util.HashMap<>();
                metadata.put("category", chunk.categories());
                return new Document(chunk.content(), metadata);
            }).toList();
        } catch (Exception e) {
            log.error("文档转换失败", e);
            throw new RuntimeException("文档转换失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量存储到向量数据库
     *
     * @param documents 要存储的文档列表
     * @return 成功存储的文档数量
     */
    private int batchStoreToVectorStore(List<Document> documents) {
        int totalDocuments = documents.size();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        log.info("开始批量存储，总文档数: {}, 批大小: {}", totalDocuments, batchSize);
        
        // 分批处理
        for (int i = 0; i < totalDocuments; i += batchSize) {
            int endIndex = Math.min(i + batchSize, totalDocuments);
            List<Document> batch = documents.subList(i, endIndex);
            int batchNumber = (i / batchSize) + 1;
            int totalBatches = (int) Math.ceil((double) totalDocuments / batchSize);
            
            log.info("处理批次 {}/{}: 文档范围 [{}-{}]", 
                batchNumber, totalBatches, i, endIndex - 1);
            
            try {
                // 重试机制
                storeBatchWithRetry(batch);
                successCount.addAndGet(batch.size());
                log.info("✓ 批次 {} 存储成功: {} 个文档", batchNumber, batch.size());
                
            } catch (Exception e) {
                failureCount.addAndGet(batch.size());
                log.error("✗ 批次 {} 存储失败: {}", batchNumber, e.getMessage(), e);
                // 继续处理下一批，不中断整个流程
            }
            
            // 添加延迟，避免对向量数据库造成过大压力
            if (i + batchSize < totalDocuments) {
                try {
                    Thread.sleep(100); // 100ms延迟
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("批处理延迟被中断", e);
                }
            }
        }
        
        log.info("批量存储完成 - 成功: {}, 失败: {}, 总计: {}", 
            successCount.get(), failureCount.get(), totalDocuments);
        
        return successCount.get();
    }

    /**
     * 带重试机制的批次存储
     */
    private void storeBatchWithRetry(List<Document> batch) {
        int attempt = 0;
        Exception lastException = null;
        
        while (attempt < maxRetries) {
            try {
                vectorStore.add(batch);
                return; // 成功则返回
            } catch (Exception e) {
                lastException = e;
                attempt++;
                if (attempt < maxRetries) {
                    log.warn("批次存储失败，尝试重试 {}/{}: {}", 
                        attempt, maxRetries, e.getMessage());
                    try {
                        Thread.sleep(1000 * attempt); // 递增延迟
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        
        // 所有重试都失败
        throw new RuntimeException("批次存储失败，已重试 " + maxRetries + " 次", lastException);
    }

    /**
     * 打印统计信息
     */
    private void printStatistics(IngestStatistics stats) {
        log.info("========================================");
        log.info("📊 数据摄取统计信息");
        log.info("========================================");
        log.info("原始文档数:     {}", stats.rawDocumentCount);
        log.info("切块数:         {}", stats.chunkCount);
        log.info("向量化文档数:   {}", stats.embeddedDocumentCount);
        log.info("成功存储数:     {}", stats.storedDocumentCount);
        log.info("总耗时:         {} 秒", stats.durationSeconds);
        
        if (stats.chunkCount > 0) {
            double avgChunksPerDoc = (double) stats.chunkCount / stats.rawDocumentCount;
            log.info("平均切块数/文档: {}", String.format("%.2f", avgChunksPerDoc));
        }
        
        if (stats.durationSeconds > 0) {
            double throughput = (double) stats.storedDocumentCount / stats.durationSeconds;
            log.info("处理速度:       {} 文档/秒", String.format("%.2f", throughput));
        }
        log.info("========================================");
    }

    /**
     * 统计信息类
     */
    private static class IngestStatistics {
        int rawDocumentCount = 0;
        int chunkCount = 0;
        int embeddedDocumentCount = 0;
        int storedDocumentCount = 0;
        long durationSeconds = 0;
    }
}
