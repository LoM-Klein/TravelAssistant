package com.ai.recommend.componet.prosessor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文档加载器
 * 支持多种数据源和文件格式的灵活加载
 *
 * @author Travel Agent
 * @since 1.0.0
 */
@Component
@Slf4j
public class DocumentLoader {

    /**
     * 从单个资源加载文档
     *
     * @param resource 资源
     * @param fileType 文件类型（txt, pdf等）
     * @return 文档列表
     */
    public List<Document> loadFromResource(Resource resource, String fileType) {
        log.info("开始加载文档资源: {}, 类型: {}", resource.getFilename(), fileType);
        
        try {
            return switch (fileType.toLowerCase()) {
                case "txt" -> loadTextDocument(resource);
                case "pdf" -> loadPdfDocument(resource);
                default -> {
                    log.warn("不支持的文件类型: {}, 尝试作为文本文件加载", fileType);
                    yield loadTextDocument(resource);
                }
            };
        } catch (Exception e) {
            log.error("加载文档资源失败: {}", resource.getFilename(), e);
            throw new RuntimeException("文档加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从目录批量加载文档
     *
     * @param directoryPath 目录路径
     * @param fileExtension 文件扩展名（如 .txt, .pdf）
     * @return 所有文档列表
     */
    public List<Document> loadFromDirectory(String directoryPath, String fileExtension) {
        log.info("开始从目录批量加载文档: {}, 扩展名: {}", directoryPath, fileExtension);
        
        List<Document> allDocuments = new ArrayList<>();
        Path directory = Paths.get(directoryPath);
        
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            log.error("目录不存在或不是有效目录: {}", directoryPath);
            throw new IllegalArgumentException("无效的目录路径: " + directoryPath);
        }
        
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> files = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(fileExtension))
                .toList();
            
            log.info("在目录 {} 中找到 {} 个 {} 文件", directoryPath, files.size(), fileExtension);
            
            for (Path filePath : files) {
                try {
                    List<Document> docs = loadFromFilePath(filePath.toString(), 
                        fileExtension.replace(".", ""));
                    allDocuments.addAll(docs);
                    log.info("成功加载文件: {}, 文档数: {}", filePath.getFileName(), docs.size());
                } catch (Exception e) {
                    log.error("加载文件失败: {}", filePath, e);
                    // 继续处理其他文件，不中断整个流程
                }
            }
            
        } catch (IOException e) {
            log.error("遍历目录失败: {}", directoryPath, e);
            throw new RuntimeException("目录遍历失败: " + e.getMessage(), e);
        }
        
        log.info("批量加载完成，总计文档数: {}", allDocuments.size());
        return allDocuments;
    }

    /**
     * 从文件路径加载文档
     *
     * @param filePath 文件路径
     * @param fileType 文件类型
     * @return 文档列表
     */
    public List<Document> loadFromFilePath(String filePath, String fileType) {
        log.info("从文件路径加载文档: {}", filePath);
        
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            log.error("文件不存在: {}", filePath);
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }
        
        try {
            Resource resource = new org.springframework.core.io.FileSystemResource(path);
            return loadFromResource(resource, fileType);
        } catch (Exception e) {
            log.error("从文件路径加载失败: {}", filePath, e);
            throw new RuntimeException("文件加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从多个资源加载文档
     *
     * @param resources 资源列表
     * @param fileType 文件类型
     * @return 所有文档列表
     */
    public List<Document> loadFromResources(List<Resource> resources, String fileType) {
        log.info("开始从 {} 个资源加载文档", resources.size());
        
        List<Document> allDocuments = new ArrayList<>();
        
        for (Resource resource : resources) {
            try {
                List<Document> docs = loadFromResource(resource, fileType);
                allDocuments.addAll(docs);
                log.info("成功加载资源: {}, 文档数: {}", 
                    resource.getFilename(), docs.size());
            } catch (Exception e) {
                log.error("加载资源失败: {}", resource.getFilename(), e);
                // 继续处理其他资源
            }
        }
        
        log.info("批量加载完成，总计文档数: {}", allDocuments.size());
        return allDocuments;
    }

    /**
     * 加载文本文档
     *
     * @param resource 资源
     * @return 文档列表
     */
    private List<Document> loadTextDocument(Resource resource) {
        try {
            TextReader textReader = new TextReader(resource);
            textReader.getCustomMetadata().put("filename", resource.getFilename());
            textReader.getCustomMetadata().put("source", "text");
            textReader.getCustomMetadata().put("load_time", System.currentTimeMillis());
            
            List<Document> documents = textReader.read();
            log.debug("成功加载文本文档: {}, 文档数: {}", 
                resource.getFilename(), documents.size());
            return documents;
        } catch (Exception e) {
            log.error("加载文本文档失败: {}", resource.getFilename(), e);
            throw new RuntimeException("文本文档加载失败", e);
        }
    }

    /**
     * 加载PDF文档
     *
     * @param resource 资源
     * @return 文档列表
     */
    private List<Document> loadPdfDocument(Resource resource) {
        try {
            PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(resource);
            List<Document> documents = pdfReader.get();
            
            // 添加元数据
            for (Document doc : documents) {
                doc.getMetadata().put("filename", resource.getFilename());
                doc.getMetadata().put("source", "pdf");
                doc.getMetadata().put("load_time", System.currentTimeMillis());
            }
            
            log.debug("成功加载PDF文档: {}, 页数: {}", 
                resource.getFilename(), documents.size());
            return documents;
        } catch (Exception e) {
            log.error("加载PDF文档失败: {}", resource.getFilename(), e);
            throw new RuntimeException("PDF文档加载失败", e);
        }
    }

    /**
     * 验证文档内容
     *
     * @param documents 文档列表
     * @return 验证通过的文档列表
     */
    public List<Document> validateDocuments(List<Document> documents) {
        log.info("开始验证文档，总数: {}", documents.size());
        
        List<Document> validDocuments = documents.stream()
            .filter(doc -> doc.getText() != null && !doc.getText().trim().isEmpty())
            .filter(doc -> doc.getText().length() >= 10) // 最小文本长度
            .toList();
        
        int invalidCount = documents.size() - validDocuments.size();
        if (invalidCount > 0) {
            log.warn("过滤掉 {} 个无效文档", invalidCount);
        }
        
        log.info("文档验证完成，有效文档数: {}", validDocuments.size());
        return validDocuments;
    }
}

