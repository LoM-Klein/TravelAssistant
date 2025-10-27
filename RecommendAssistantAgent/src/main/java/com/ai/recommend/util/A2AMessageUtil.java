package com.ai.recommend.util;
import com.travelassistant.common.model.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A2A 消息和任务工具类
 * <p>
 * 提供 A2A 协议相关的消息创建、解析和任务处理工具方法
 *
 * @author Travel Assistant Team
 * @version 1.0.0
 */
public final class A2AMessageUtil {

    /**
     * 私有构造函数，防止实例化
     */
    private A2AMessageUtil() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== 消息提取方法 ====================

    /**
     * 从消息中提取文本内容
     * <p>
     * 遍历消息的所有部分，提取 TextPart 类型的文本内容，
     * 多个文本部分用换行符连接
     *
     * @param message A2A 消息对象
     * @return 提取的文本内容，如果消息为空或没有文本部分则返回 null
     */
    public static String extractTextFromMessage(Message message) {
        if (message == null || message.parts() == null || message.parts().isEmpty()) {
            return null;
        }

        return message.parts().stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).text())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 从消息中提取第一个文本部分
     *
     * @param message A2A 消息对象
     * @return 第一个文本内容，如果不存在则返回 null
     */
    public static String extractFirstText(Message message) {
        if (message == null || message.parts() == null || message.parts().isEmpty()) {
            return null;
        }

        return message.parts().stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).text())
                .findFirst()
                .orElse(null);
    }

    // ==================== 消息创建方法 ====================

    /**
     * 创建文本消息
     *
     * @param text      消息文本内容
     * @param role      消息角色（user, assistant, system）
     * @param contextId 会话 ID
     * @param taskId    任务 ID
     * @param inReplyTo 回复的消息 ID 列表
     * @return Message 对象
     */
    public static Message createTextMessage(
            String text,
            String role,
            String contextId,
            String taskId,
            List<String> inReplyTo
    ) {
        TextPart textPart = new TextPart(text, null);

        return new Message(
                UUID.randomUUID().toString(),  // messageId
                "message",                      // kind
                role,                           // role
                List.of(textPart),             // parts
                contextId,                      // contextId
                taskId,                         // taskId
                inReplyTo,                      // inReplyTo
                null                            // metadata
        );
    }

    /**
     * 创建助手响应消息
     * <p>
     * 根据用户消息和任务信息创建助手的回复消息
     *
     * @param userMessage  用户消息
     * @param task         当前任务
     * @param responseText 响应文本内容
     * @return 助手响应消息
     */
    public static Message createAssistantResponse(Message userMessage, Task task, String responseText) {
        return createTextMessage(
                responseText,
                "assistant",
                userMessage.contextId(),
                task.id(),
                List.of(userMessage.messageId())
        );
    }

    /**
     * 创建系统消息
     *
     * @param text      系统消息文本
     * @param contextId 会话 ID
     * @param taskId    任务 ID
     * @return 系统消息
     */
    public static Message createSystemMessage(String text, String contextId, String taskId) {
        return createTextMessage(text, "system", contextId, taskId, null);
    }

    // ==================== 任务状态方法 ====================

    /**
     * 创建任务状态
     *
     * @param state   任务状态
     * @param message 状态消息（可选）
     * @return TaskStatus 对象
     */
    public static TaskStatus createTaskStatus(TaskState state, Message message) {
        return new TaskStatus(
                state,
                message,
                Instant.now().toString()
        );
    }

    /**
     * 创建任务状态（无状态消息）
     *
     * @param state 任务状态
     * @return TaskStatus 对象
     */
    public static TaskStatus createTaskStatus(TaskState state) {
        return createTaskStatus(state, null);
    }

    /**
     * 创建完成状态
     *
     * @return 完成状态的 TaskStatus
     */
    public static TaskStatus createCompletedStatus() {
        return createTaskStatus(TaskState.COMPLETED, null);
    }

    /**
     * 创建工作中状态
     *
     * @return 工作中状态的 TaskStatus
     */
    public static TaskStatus createWorkingStatus() {
        return createTaskStatus(TaskState.WORKING, null);
    }

    /**
     * 创建失败状态（带错误消息）
     *
     * @param errorMessage 错误消息文本
     * @param contextId    会话 ID
     * @param taskId       任务 ID
     * @return 失败状态的 TaskStatus
     */
    public static TaskStatus createFailedStatus(String errorMessage, String contextId, String taskId) {
        Message errorMsg = createSystemMessage(errorMessage, contextId, taskId);
        return createTaskStatus(TaskState.FAILED, errorMsg);
    }

    // ==================== 任务创建方法 ====================

    /**
     * 创建错误任务
     * <p>
     * 当任务处理失败时，创建一个失败状态的任务对象
     *
     * @param originalTask 原始任务
     * @param errorMessage 错误消息
     * @return 错误状态的任务
     */
    public static Task createErrorTask(Task originalTask, String errorMessage) {
        TaskStatus errorStatus = createFailedStatus(
                errorMessage,
                originalTask.contextId(),
                originalTask.id()
        );

        return new Task(
                originalTask.id(),
                originalTask.contextId(),
                originalTask.kind(),
                errorStatus,
                originalTask.artifacts(),
                originalTask.history(),
                originalTask.metadata()
        );
    }

    /**
     * 更新任务状态
     *
     * @param task      原始任务
     * @param newStatus 新状态
     * @return 更新状态后的任务
     */
    public static Task updateTaskStatus(Task task, TaskStatus newStatus) {
        return new Task(
                task.id(),
                task.contextId(),
                task.kind(),
                newStatus,
                task.artifacts(),
                task.history(),
                task.metadata()
        );
    }

    /**
     * 向任务历史添加消息
     *
     * @param task     原始任务
     * @param messages 要添加的消息列表
     * @return 更新历史后的任务
     */
    public static Task appendMessageToHistory(Task task, Message... messages) {
        List<Message> updatedHistory = new java.util.ArrayList<>();
        if (task.history() != null) {
            updatedHistory.addAll(task.history());
        }
        updatedHistory.addAll(List.of(messages));

        return new Task(
                task.id(),
                task.contextId(),
                task.kind(),
                task.status(),
                task.artifacts(),
                updatedHistory,
                task.metadata()
        );
    }

    // ==================== 验证方法 ====================

    /**
     * 检查消息是否包含文本内容
     *
     * @param message 消息对象
     * @return 如果包含文本内容返回 true，否则返回 false
     */
    public static boolean hasTextContent(Message message) {
        String text = extractTextFromMessage(message);
        return text != null && !text.trim().isEmpty();
    }

    /**
     * 检查任务是否处于最终状态
     * <p>
     * 最终状态包括：COMPLETED, FAILED, CANCELED
     *
     * @param task 任务对象
     * @return 如果是最终状态返回 true，否则返回 false
     */
    public static boolean isTaskInFinalState(Task task) {
        if (task == null || task.status() == null) {
            return false;
        }

        TaskState state = task.status().state();
        return state == TaskState.COMPLETED
                || state == TaskState.FAILED
                || state == TaskState.CANCELED;
    }

    /**
     * 检查任务是否可以被取消
     *
     * @param task 任务对象
     * @return 如果可以取消返回 true，否则返回 false
     */
    public static boolean isTaskCancelable(Task task) {
        return !isTaskInFinalState(task);
    }
}

