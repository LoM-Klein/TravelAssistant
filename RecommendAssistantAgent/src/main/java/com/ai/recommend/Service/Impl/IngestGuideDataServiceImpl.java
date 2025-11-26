package com.ai.recommend.Service.Impl;

import com.ai.recommend.rag.ETL.transformer.KeywordEnricher;
import com.ai.recommend.Service.IngestGuideDataService;
import com.ai.recommend.rag.ETL.reader.MyTextReader;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import java.util.List;


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

    @Resource
    private VectorStore vectorStore;
    @Resource
    private MyTextReader myTextReader;
    @Resource
    private KeywordEnricher keywordEnricher;

    public void ingestGuideData() {
        log.info("开始攻略数据摄取流程");
        try {
            // 第1步: 提取 (Extract)
            log.info("【步骤 1/4】提取原始数据...");
            List<Document> rawDocuments = myTextReader.loadText();
            log.info("提取完成: 共 {} 个原始文档", rawDocuments.size());
            if (rawDocuments.isEmpty()) {
                log.warn("没有找到任何文档，摄取流程终止");
                return;
            }
            // 第2步: 语义切块和分类 (Transform - Chunking & Classification)
            log.info("【步骤 2/3】语义切块和分类（调用 LLM）...");
            TokenTextSplitter myTextSplitter = TokenTextSplitter.builder().withChunkSize(1000).build();
            List<Document> documents = keywordEnricher.apply(myTextSplitter.split(rawDocuments));

            // 第3步: 转换为 Document 并批量存储（VectorStore 会自动进行向量化）
            log.info("【步骤 3/3】转换文档并存储到向量数据库）...");
                vectorStore.add(documents);

        } catch (Exception e) {
            log.error("数据摄取流程失败");
            log.error("错误详情: {}", e.getMessage(), e);
            throw new RuntimeException("数据摄取失败: " + e.getMessage(), e);
        }
    }

}


