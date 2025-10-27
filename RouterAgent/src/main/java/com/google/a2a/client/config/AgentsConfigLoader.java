package com.google.a2a.client.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 配置加载器
 * <p>
 * 从 YAML 或 JSON 文件加载 Agent 配置
 */
public class AgentsConfigLoader {
    
    private static final Logger logger = LoggerFactory.getLogger(AgentsConfigLoader.class);
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper jsonMapper = new ObjectMapper();
    
    /**
     * 从 YAML 文件加载配置
     */
    public static List<AgentConfig> loadFromYaml(String filePath) throws IOException {
        logger.info("Loading agent configurations from YAML file: {}", filePath);
        File file = new File(filePath);
        return parseYaml(file);
    }
    
    /**
     * 从 classpath 资源加载 YAML 配置
     */
    public static List<AgentConfig> loadFromClasspath(String resourcePath) throws IOException {
        logger.info("Loading agent configurations from classpath: {}", resourcePath);
        try (InputStream is = AgentsConfigLoader.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return parseYaml(is);
        }
    }
    
    /**
     * 从 JSON 文件加载配置
     */
    public static List<AgentConfig> loadFromJson(String filePath) throws IOException {
        logger.info("Loading agent configurations from JSON file: {}", filePath);
        File file = new File(filePath);
        return parseJson(file);
    }
    
    /**
     * 解析 YAML 文件
     */
    private static List<AgentConfig> parseYaml(File file) throws IOException {
        Map<String, Object> config = yamlMapper.readValue(file, Map.class);
        return parseConfig(config);
    }
    
    /**
     * 解析 YAML 输入流
     */
    private static List<AgentConfig> parseYaml(InputStream inputStream) throws IOException {
        Map<String, Object> config = yamlMapper.readValue(inputStream, Map.class);
        return parseConfig(config);
    }
    
    /**
     * 解析 JSON 文件
     */
    private static List<AgentConfig> parseJson(File file) throws IOException {
        Map<String, Object> config = jsonMapper.readValue(file, Map.class);
        return parseConfig(config);
    }
    
    /**
     * 解析配置 Map
     */
    @SuppressWarnings("unchecked")
    private static List<AgentConfig> parseConfig(Map<String, Object> config) {
        List<AgentConfig> agents = new ArrayList<>();
        
        Object agentsObj = config.get("agents");
        if (!(agentsObj instanceof List)) {
            logger.warn("No 'agents' list found in configuration");
            return agents;
        }
        
        List<Map<String, Object>> agentsList = (List<Map<String, Object>>) agentsObj;
        
        for (Map<String, Object> agentMap : agentsList) {
            try {
                AgentConfig agentConfig = parseAgentConfig(agentMap);
                agents.add(agentConfig);
                logger.debug("Loaded agent configuration: {}", agentConfig.name());
            } catch (Exception e) {
                logger.error("Failed to parse agent configuration: {}", agentMap, e);
            }
        }
        
        logger.info("Successfully loaded {} agent configurations", agents.size());
        return agents;
    }
    
    /**
     * 解析单个 Agent 配置
     */
    @SuppressWarnings("unchecked")
    private static AgentConfig parseAgentConfig(Map<String, Object> agentMap) {
        AgentConfig.Builder builder = AgentConfig.builder();
        
        // 必需字段
        String name = (String) agentMap.get("name");
        String url = (String) agentMap.get("url");
        
        if (name == null || url == null) {
            throw new IllegalArgumentException("Agent name and url are required");
        }
        
        builder.name(name).url(url);
        
        // 可选字段
        if (agentMap.containsKey("description")) {
            builder.description((String) agentMap.get("description"));
        }
        
        if (agentMap.containsKey("skills")) {
            Object skillsObj = agentMap.get("skills");
            if (skillsObj instanceof List) {
                builder.skills((List<String>) skillsObj);
            }
        }
        
        if (agentMap.containsKey("enabled")) {
            builder.enabled((Boolean) agentMap.get("enabled"));
        }
        
        if (agentMap.containsKey("priority")) {
            builder.priority(((Number) agentMap.get("priority")).intValue());
        }
        
        if (agentMap.containsKey("timeout")) {
            builder.timeout(((Number) agentMap.get("timeout")).longValue());
        }
        
        if (agentMap.containsKey("retryAttempts")) {
            builder.retryAttempts(((Number) agentMap.get("retryAttempts")).intValue());
        }
        
        if (agentMap.containsKey("healthCheckInterval")) {
            builder.healthCheckInterval(((Number) agentMap.get("healthCheckInterval")).intValue());
        }
        
        if (agentMap.containsKey("metadata")) {
            builder.metadata((Map<String, Object>) agentMap.get("metadata"));
        }
        
        return builder.build();
    }
}

