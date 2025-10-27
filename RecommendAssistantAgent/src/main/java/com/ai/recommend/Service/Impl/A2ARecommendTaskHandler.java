package com.ai.recommend.Service.Impl;

import com.ai.recommend.Service.RecommendAssistant;
import com.travelassistant.common.model.Message;
import com.travelassistant.common.model.Task;
import com.travelassistant.common.model.TaskStatus;
import com.ai.recommend.Service.TaskHandler;
import com.ai.recommend.util.A2AMessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * A2A 推荐任务处理器
 * <p>
 * 负责将 A2A 消息格式转换为 RecommendAssistant 可处理的格式
 * 并将响应结果转换回 A2A 格式
 *
 * @author Travel Assistant Team
 * @version 1.0.0
 */
public class A2ARecommendTaskHandler implements TaskHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(A2ARecommendTaskHandler.class);
    
    private final RecommendAssistant recommendAssistant;

    /**
     * 构造函数
     *
     * @param recommendAssistant 旅行推荐服务
     */
    public A2ARecommendTaskHandler(RecommendAssistant recommendAssistant) {
        this.recommendAssistant = recommendAssistant;
    }

    /**
     * 处理 A2A 任务
     * <p>
     * 主要流程：
     * 1. 提取用户消息文本
     * 2. 调用 RecommendAssistant 进行查询
     * 3. 收集响应结果
     * 4. 创建响应消息并更新任务状态
     *
     * @param task    当前任务
     * @param message 用户消息
     * @return 处理后的任务
     */
    @Override
    public Task handle(Task task, Message message) {
        logger.info("处理 A2A 任务: taskId={}, messageId={}", task.id(), message.messageId());
        
        try {
            // 1. 从消息中提取文本内容
            String userQuery = A2AMessageUtil.extractTextFromMessage(message);
            
            if (userQuery == null || userQuery.trim().isEmpty()) {
                logger.warn("消息中没有文本内容");
                return A2AMessageUtil.createErrorTask(task, "No text content found in the message");
            }
            
            logger.info("用户查询: {}", userQuery);
            
            // 2. 使用 contextId 作为 chatId（保持会话连续性）
            String chatId = task.contextId() != null ? task.contextId() : UUID.randomUUID().toString();
            
            // 3. 调用 RecommendAssistant 进行查询（收集流式响应）
            Flux<ChatResponse> responseFlux = recommendAssistant.travelQuery(chatId, userQuery);
            
            // 4. 收集所有流式响应内容
            String responseText = collectResponseText(responseFlux);
            
            if (responseText.isEmpty()) {
                logger.warn("推荐服务返回空响应");
                responseText = "抱歉，我暂时无法为您提供推荐。";
            }
            
            logger.info("生成响应，长度: {}", responseText.length());
            
            // 5. 创建响应消息
            Message responseMessage = A2AMessageUtil.createAssistantResponse(message, task, responseText);
            
            // 6. 更新任务历史
            Task taskWithHistory = A2AMessageUtil.appendMessageToHistory(task, message, responseMessage);
            
            // 7. 更新为完成状态
            TaskStatus completedStatus = A2AMessageUtil.createCompletedStatus();
            return A2AMessageUtil.updateTaskStatus(taskWithHistory, completedStatus);
            
        } catch (Exception e) {
            logger.error("处理任务失败", e);
            return A2AMessageUtil.createErrorTask(task, e.getMessage());
        }
    }

    /**
     * 收集流式响应的文本内容
     *
     * @param responseFlux 响应流
     * @return 完整的响应文本
     */
    private String collectResponseText(Flux<ChatResponse> responseFlux) {
        StringBuilder fullResponse = new StringBuilder();
        
        responseFlux.toStream().forEach(chatResponse -> {
            chatResponse.getResult();
            chatResponse.getResult();
            if (chatResponse.getResult().getOutput().getText() != null) {
                fullResponse.append(chatResponse.getResult().getOutput().getText());
            }
        });
        
        return fullResponse.toString();
    }
}

