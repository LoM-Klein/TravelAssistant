package com.ai.recommend.componet.prosessor;


import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.ai.reader.TextReader;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本读取器
 * 支持配置化的文本数据源加载
 *
 * @author Travel Agent
 * @since 1.0.0
 */
@Component
@Slf4j
public class MyTextReader {

    private final Resource[] resources;
    private final DocumentLoader documentLoader;

    /**
     * 构造函数，支持单个或多个文件配置
     *
     * @param resources 文本资源，支持逗号分隔的多个文件
     * @param documentLoader 文档加载器
     */
    MyTextReader(
        @Value("${app.data.source:classpath:data/travel_guide_test_data.txt}") Resource[] resources,
        DocumentLoader documentLoader) {
        this.resources = resources;
        this.documentLoader = documentLoader;
        log.info("初始化文本读取器，配置了 {} 个数据源", resources.length);
    }

    /**
     * 加载文本数据
     * 支持从配置的多个资源加载
     *
     * @return 文档列表
     */
    public List<Document> loadText() {
        log.info("开始加载文本数据，数据源数量: {}", resources.length);
        
        List<Document> allDocuments = new ArrayList<>();
        
        for (Resource resource : resources) {
            try {
                log.info("正在加载资源: {}", resource.getFilename());
                TextReader textReader = new TextReader(resource);
                
                // 添加元数据
                textReader.getCustomMetadata().put("filename", resource.getFilename());
                textReader.getCustomMetadata().put("source_type", "text");
                textReader.getCustomMetadata().put("load_timestamp", System.currentTimeMillis());
                
                List<Document> docs = textReader.read();
                allDocuments.addAll(docs);
                
                log.info("成功加载资源: {}, 文档数: {}", resource.getFilename(), docs.size());
                
            } catch (Exception e) {
                log.error("加载资源失败: {}, 错误: {}", 
                    resource.getDescription(), e.getMessage(), e);
                // 继续处理其他资源，不中断整个流程
            }
        }
        
        // 验证文档
        allDocuments = documentLoader.validateDocuments(allDocuments);
        
        log.info("文本数据加载完成，总计文档数: {}", allDocuments.size());
        return allDocuments;
    }

    /**
     * 从指定资源加载
     *
     * @param resource 资源
     * @return 文档列表
     */
    public List<Document> loadFromResource(Resource resource) {
        log.info("从指定资源加载: {}", resource.getDescription());
        
        try {
            TextReader textReader = new TextReader(resource);
            textReader.getCustomMetadata().put("filename", resource.getFilename());
            textReader.getCustomMetadata().put("source_type", "text");
            
            List<Document> docs = textReader.read();
            log.info("成功加载，文档数: {}", docs.size());
            return docs;
            
        } catch (Exception e) {
            log.error("加载资源失败: {}", resource.getDescription(), e);
            throw new RuntimeException("文本加载失败: " + e.getMessage(), e);
        }
    }
}
