package com.ai.recommend.componet.prosessor;
import com.ai.recommend.model.ClassifiedChunks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class EmbeddingDocuments {
    private final EmbeddingModel embeddingModel;
    
    // DashScope Embedding API 的批处理限制
    private static final int EMBEDDING_BATCH_SIZE = 25;

    // 注入 Spring AI 自动配置的 EmbeddingModel (例如 Embedding-v1 的实现)
    public EmbeddingDocuments(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 对提供的文档列表进行向量化操作。
     * * @param documents 包含 content 和 metadata 的 Document 列表。
     * @return 包含 content_vector 元数据的新 Document 列表。
     */
    public List<Document> embedDocuments(ClassifiedChunks classifiedData) {
        if (classifiedData == null || classifiedData.chunks().isEmpty()) {
            return List.of();
        }
        // 步骤 A: 转换 (Transform) - 从 ClassifiedChunks 到 List<Document>
        List<Document> documents = classifiedData.chunks().stream().map(chunk -> {
            // Document 构造函数通常是 Document(String content, Map<String, Object> metadata)
            Map<String, Object> metadata = new java.util.HashMap<>();
            // 关键元数据：类别标签
            metadata.put("category", chunk.categories());
            return new Document(chunk.content(), metadata);
        }).toList();

        // 1. 提取所有文档的内容
        List<String> contents = documents.stream()
                .map(Document::getText)
                .collect(Collectors.toList());

        // 2. 批量调用 Embedding 模型生成向量（分批处理以避免超过 API 限制）
        log.info("开始向量化 {} 个文档（批大小: {}）...", contents.size(), EMBEDDING_BATCH_SIZE);
        List<float[]> embeddings = embedInBatches(contents);

        if (embeddings.size() != documents.size()) {
            throw new IllegalStateException("Embedding 结果数量与文档切块数量不匹配！");
        }

        // 3. 将生成的向量赋值给对应的 Document 的元数据
        List<Document> embeddedDocuments = new java.util.ArrayList<>();

        for (int i = 0; i < documents.size(); i++) {
            Document originalDoc = documents.get(i);
            float[] vector = embeddings.get(i);

            // 创建一个新的 Document 实例，避免修改原始列表中的对象（可选，但更安全）
            Document embeddedDoc = new Document(originalDoc.getText(), originalDoc.getMetadata());

            // 将向量存储在元数据中。键名 'content_vector' 用于清晰地表示其用途。
            embeddedDoc.getMetadata().put("content_vector", vector);

            // Spring AI 的 VectorStore 实现通常会自动处理这个字段。
            embeddedDocuments.add(embeddedDoc);
        }

        log.info("向量化完成，共 {} 个文档", embeddedDocuments.size());
        return embeddedDocuments;
    }
    
    /**
     * 分批进行向量化，避免超过 API 限制
     * 
     * @param contents 要向量化的文本列表
     * @return 向量列表
     */
    private List<float[]> embedInBatches(List<String> contents) {
        List<float[]> allEmbeddings = new ArrayList<>();
        int totalContents = contents.size();
        int totalBatches = (int) Math.ceil((double) totalContents / EMBEDDING_BATCH_SIZE);
        
        log.info("总文本数: {}, 将分为 {} 批处理", totalContents, totalBatches);
        
        for (int i = 0; i < totalContents; i += EMBEDDING_BATCH_SIZE) {
            int endIndex = Math.min(i + EMBEDDING_BATCH_SIZE, totalContents);
            List<String> batch = contents.subList(i, endIndex);
            int batchNumber = (i / EMBEDDING_BATCH_SIZE) + 1;
            
            log.info("处理向量化批次 {}/{}: 文本范围 [{}-{}], 数量 {}", 
                batchNumber, totalBatches, i, endIndex - 1, batch.size());
            
            try {
                // 调用 Embedding API
                List<float[]> batchEmbeddings = embeddingModel.embed(batch);
                allEmbeddings.addAll(batchEmbeddings);
                
                log.info("✓ 批次 {} 向量化成功，获得 {} 个向量", batchNumber, batchEmbeddings.size());
                
                // 添加短暂延迟，避免 API 限流
                if (i + EMBEDDING_BATCH_SIZE < totalContents) {
                    try {
                        Thread.sleep(100); // 100ms 延迟
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("向量化批处理延迟被中断", e);
                    }
                }
                
            } catch (Exception e) {
                log.error("✗ 批次 {} 向量化失败: {}", batchNumber, e.getMessage(), e);
                throw new RuntimeException("向量化批次 " + batchNumber + " 失败: " + e.getMessage(), e);
            }
        }
        
        log.info("所有批次向量化完成，共生成 {} 个向量", allEmbeddings.size());
        return allEmbeddings;
    }
}
