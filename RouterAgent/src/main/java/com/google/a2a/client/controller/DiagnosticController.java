package com.google.a2a.client.controller;

import com.google.a2a.client.manager.AgentManager;
import com.google.a2a.client.manager.AgentRegistry;
import com.travelassistant.common.model.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断控制器
 * <p>
 * 用于诊断 Agent 注册和 AgentCard 缓存状态
 */
@RestController
@RequestMapping("/diagnostic")
public class DiagnosticController {
    
    private static final Logger logger = LoggerFactory.getLogger(DiagnosticController.class);
    
    private final AgentManager agentManager;
    private final AgentRegistry agentRegistry;
    
    public DiagnosticController(AgentManager agentManager, AgentRegistry agentRegistry) {
        this.agentManager = agentManager;
        this.agentRegistry = agentRegistry;
    }
    
    /**
     * 获取所有 Agent 的状态
     */
    @GetMapping("/agents")
    public Map<String, Object> getAgentStatus() {
        logger.info("Diagnostic: Getting all agents status");
        
        List<AgentManager.AgentInfo> agents = agentManager.getAllAgents();
        
        Map<String, Object> result = new HashMap<>();
        result.put("totalAgents", agents.size());
        result.put("agents", agents.stream().map(agent -> {
            Map<String, Object> agentInfo = new HashMap<>();
            agentInfo.put("name", agent.name());
            agentInfo.put("healthy", agent.healthy());
            agentInfo.put("hasConfig", agent.config() != null);
            agentInfo.put("hasAgentCard", agent.card() != null);
            
            if (agent.config() != null) {
                agentInfo.put("configUrl", agent.config().url());
                agentInfo.put("configEnabled", agent.config().enabled());
            }
            
            if (agent.card() != null) {
                agentInfo.put("cardName", agent.card().name());
                agentInfo.put("cardDescription", agent.card().description());
                agentInfo.put("cardSkillsCount", 
                        agent.card().skills() != null ? agent.card().skills().size() : 0);
            } else {
                agentInfo.put("warning", "AgentCard is null - this may affect intent analysis");
            }
            
            return agentInfo;
        }).toList());
        
        return result;
    }
    
    /**
     * 获取指定 Agent 的详细信息
     */
    @GetMapping("/agents/{agentName}")
    public Map<String, Object> getAgentDetail(@PathVariable String agentName) {
        logger.info("Diagnostic: Getting agent detail for: {}", agentName);
        
        Map<String, Object> result = new HashMap<>();
        result.put("agentName", agentName);
        
        // 检查配置
        var configOpt = agentRegistry.getConfig(agentName);
        result.put("hasConfig", configOpt.isPresent());
        if (configOpt.isPresent()) {
            var config = configOpt.get();
            result.put("config", Map.of(
                    "url", config.url(),
                    "enabled", config.enabled(),
                    "skills", config.skills(),
                    "description", config.description()
            ));
        }
        
        // 检查 AgentCard
        var cardOpt = agentRegistry.getAgentCard(agentName);
        result.put("hasAgentCard", cardOpt.isPresent());
        if (cardOpt.isPresent()) {
            AgentCard card = cardOpt.get();
            result.put("agentCard", Map.of(
                    "name", card.name(),
                    "description", card.description(),
                    "url", card.url(),
                    "version", card.version(),
                    "skillsCount", card.skills() != null ? card.skills().size() : 0,
                    "skills", card.skills() != null ? card.skills().stream()
                            .map(skill -> Map.of(
                                    "name", skill.name(),
                                    "description", skill.description(),
                                    "tags", skill.tags()
                            ))
                            .toList() : List.of()
            ));
        } else {
            result.put("error", "AgentCard not found in registry");
            result.put("suggestion", "Check if agent discovery was successful during startup");
        }
        
        // 检查健康状态
        result.put("healthy", agentRegistry.isHealthy(agentName));
        
        // 检查客户端
        var clientOpt = agentRegistry.getClient(agentName);
        result.put("hasClient", clientOpt.isPresent());
        
        return result;
    }
    
    /**
     * 获取健康的 Agent 列表
     */
    @GetMapping("/healthy-agents")
    public Map<String, Object> getHealthyAgents() {
        logger.info("Diagnostic: Getting healthy agents");
        
        List<String> healthyAgents = agentRegistry.getHealthyAgents();
        
        return Map.of(
                "count", healthyAgents.size(),
                "agents", healthyAgents
        );
    }
    
    /**
     * 获取所有注册的 Agent 名称
     */
    @GetMapping("/registered-agents")
    public Map<String, Object> getRegisteredAgents() {
        logger.info("Diagnostic: Getting registered agents");
        
        var allNames = agentRegistry.getAllAgentNames();
        
        return Map.of(
                "count", allNames.size(),
                "agents", allNames
        );
    }
}

