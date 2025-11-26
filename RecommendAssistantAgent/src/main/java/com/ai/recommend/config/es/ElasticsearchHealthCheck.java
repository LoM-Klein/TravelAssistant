package com.ai.recommend.config.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.elasticsearch.autoconfigure.ElasticsearchVectorStoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch健康检查组件
 * 在应用启动时自动检查ES连接状态
 */
@Component
public class ElasticsearchHealthCheck implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchHealthCheck.class);

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private ElasticsearchVectorStoreProperties vectorStoreProperties;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        logger.info("开始检查Elasticsearch连接状态...");
        
        try {
            // 检查ES连接
            HealthResponse health = elasticsearchClient.cluster().health();
            String status = health.status().toString();
            
            logger.info("=== Elasticsearch连接状态 ===");
            logger.info("集群名称: {}", health.clusterName());
            logger.info("集群状态: {}", status);
            logger.info("节点数量: {}", health.numberOfNodes());
            logger.info("数据节点数量: {}", health.numberOfDataNodes());
            logger.info("活跃分片: {}", health.activeShards());
            logger.info("活跃主分片: {}", health.activePrimaryShards());
            logger.info("未分配分片: {}", health.unassignedShards());
            
            // 检查向量存储配置
            logger.info("=== 向量存储配置 ===");
            logger.info("索引名称: {}", vectorStoreProperties.getIndexName());
            logger.info("向量维度: {}", vectorStoreProperties.getDimensions());
            logger.info("相似度算法: {}", vectorStoreProperties.getSimilarity());
            
            if ("GREEN".equals(status) || "YELLOW".equals(status)) {
                logger.info("✅ Elasticsearch连接正常，系统可以正常操作ES");
            } else {
                logger.warn("⚠️ Elasticsearch集群状态异常: {}", status);
            }
            
        } catch (Exception e) {
            logger.error("❌ Elasticsearch连接失败: {}", e.getMessage());
            logger.error("请检查以下配置:");
            logger.error("1. ES服务是否启动 (https://127.0.0.1:9200)");
            logger.error("2. 用户名密码是否正确 (elastic/dCuqwL-DdnRFiBXjRQ0_)");
            logger.error("3. 网络连接是否正常");
            throw new RuntimeException("Elasticsearch连接失败，应用启动中止", e);
        }
    }
}
